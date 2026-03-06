package com.rorm.dataimport.pipeline.profile;

import com.rorm.dataimport.pipeline.profile.AttributeStatistics.*;
import com.rorm.dataimport.pipeline.profile.SchemaProfile.*;
import com.rorm.engine.ExpressionAnalyzer;
import com.rorm.engine.TypeCategory;
import com.rorm.fetcher.Fetcher;
import com.rorm.metamodel.*;
import com.rorm.query.Path;
import com.rorm.query.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Joiner;
import java.util.concurrent.StructuredTaskScope.Subtask;


@Slf4j
@Component
@RequiredArgsConstructor
public class SchemaAnalyzer {

    private final ExpressionAnalyzer expressionAnalyzer;
    private final Fetcher fetcher;

    public SchemaProfile analyze(String schema, ModelSpace modelSpace) {
        var entities = new ArrayList<EntityProfile>();
        var relationships = new ArrayList<Relationship>();

        for (var root : modelSpace.roots()) {
            var rowCount = countRows(schema, root);
            var attributes = analyzeAttributes(schema, root);
            var flags = deriveFlags(attributes, rowCount);
            entities.add(new EntityProfile(root.primaryTableName(), rowCount, flags, attributes));
            collectRelationships(root, relationships);
        }

        return new SchemaProfile(List.copyOf(entities), List.copyOf(relationships));
    }

    @SuppressWarnings("unchecked")
    private long countRows(String schema, Root root) {
        var query = Query.builder()
            .from(AliasedRoot.of(root))
            .selector(new com.rorm.query.Selector.SingleExprSelector(
                new com.rorm.query.Expression.Aggregation("COUNT",
                    List.of(new com.rorm.query.Expression.Literal("*")), false),
                false, "row_count"))
            .build();
        var results = fetcher.withSchema(schema, () ->
            fetcher.queryForType(query, () -> (Class<Map<String, Object>>) (Class<?>) Map.class));
        if (results.isEmpty()) {
            return 0;
        }
        return toLong(results.getFirst().get("row_count"));
    }

    private List<AttributeProfile> analyzeAttributes(String schema, Root root) {
        var analyzable = root.attributes().stream()
            .filter(attr -> attr instanceof BasicAttribute || attr instanceof ReferenceAttribute)
            .toList();

        var profiles = new ArrayList<AttributeProfile>();

        try (var scope = StructuredTaskScope.open(Joiner.<AttributeProfile>allSuccessfulOrThrow())) {
            for (var attr : analyzable) {
                scope.fork(() -> analyzeAttribute(schema, root, attr));
            }
            //noinspection ConstantValue
            scope.join().map(Subtask::get)
                .filter(Objects::nonNull)
                .forEach(profiles::add);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Attribute analysis interrupted for '{}', falling back to sequential",
                root.primaryTableName(), e);
        } catch (Exception e) {
            log.warn("Concurrent attribute analysis failed for '{}', falling back to sequential",
                root.primaryTableName(), e);
            for (var attr : analyzable) {
                var result = analyzeAttribute(schema, root, attr);
                if (result != null) {
                    profiles.add(result);
                }
            }
        }

        return List.copyOf(profiles);
    }

    private Set<SummaryFlag> deriveFlags(List<AttributeProfile> attributes, long rowCount) {
        var flags = EnumSet.noneOf(SummaryFlag.class);
        for (var attr : attributes) {
            switch (attr.category()) {
                case TEMPORAL -> flags.add(SummaryFlag.HAS_TEMPORAL);
                case NUMERIC -> flags.add(SummaryFlag.HAS_NUMERIC);
                case CATEGORICAL -> flags.add(SummaryFlag.HAS_CATEGORICAL);
                case BOOLEAN -> flags.add(SummaryFlag.HAS_BOOLEAN);
                default -> {
                }
            }
            if (attr.statistics().distinctCount() > 1000) {
                flags.add(SummaryFlag.HIGH_CARDINALITY);
            }
            if (attr.statistics().nullCount() > attr.statistics().totalCount() / 2) {
                flags.add(SummaryFlag.SPARSE_DATA);
            }
        }
        if (rowCount < 100) {
            flags.add(SummaryFlag.SMALL_DATASET);
        }
        if (rowCount > 100_000) {
            flags.add(SummaryFlag.LARGE_DATASET);
        }
        return flags;
    }

    private void collectRelationships(Root root, List<Relationship> relationships) {
        for (var attr : root.attributes()) {
            switch (attr) {
                case SingularReferenceAttribute ref -> relationships.add(new Relationship(
                    root.primaryTableName(),
                    ref.targetRoot().primaryTableName(),
                    ref.name(),
                    Cardinality.MANY_TO_ONE
                ));
                case PluralReferenceAttribute ref -> relationships.add(new Relationship(
                    root.primaryTableName(),
                    ref.targetRoot().primaryTableName(),
                    ref.name(),
                    Cardinality.ONE_TO_MANY
                ));
                default -> {
                }
            }
        }
    }

    private static long toLong(@Nullable Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private @Nullable AttributeProfile analyzeAttribute(String schema, Root root, Attribute attribute) {
        try {
            var path = new Path(attribute);
            var result = expressionAnalyzer.analyze(schema, root, path);
            var statistics = mapToStatistics(result.category(), attribute, result.rows());
            var dataTypeName = attribute instanceof BasicAttribute ba
                ? ba.dataType().getClass().getSimpleName()
                : "Reference";
            return new AttributeProfile(attribute.name(), dataTypeName, result.category(), statistics);
        } catch (Exception e) {
            log.warn("Failed to analyze attribute '{}.{}': {}",
                root.primaryTableName(), attribute.name(), e.getMessage());
            return null;
        }
    }

    private AttributeStatistics mapToStatistics(TypeCategory category, Attribute attribute,
                                                List<Map<String, Object>> results) {
        if (results.isEmpty()) {
            return new BasicStatistics(0, 0, 0);
        }

        return switch (category) {
            case NUMERIC -> mapNumericStats(results.getFirst());
            case TEMPORAL -> mapTemporalStats(results.getFirst());
            case BOOLEAN -> mapBooleanStats(results.getFirst());
            case CATEGORICAL -> mapCategoricalStats(results);
            case REFERENCE -> mapReferenceStats(attribute, results.getFirst());
            default -> mapBasicStats(results.getFirst());
        };
    }

    private NumericStatistics mapNumericStats(Map<String, Object> row) {
        long totalCount = toLong(row.get("total_count"));
        long nonNullCount = toLong(row.get("non_null_count"));
        long nullCount = totalCount - nonNullCount;
        var distinctCount = estimateDistinctCount(totalCount);
        return new NumericStatistics(
            totalCount, nullCount, distinctCount,
            toBigDecimal(row.get("min")),
            toBigDecimal(row.get("max")),
            toBigDecimal(row.get("avg")),
            toBigDecimal(row.get("stddev")),
            toBigDecimal(row.get("sum"))
        );
    }

    private TemporalStatistics mapTemporalStats(Map<String, Object> row) {
        long totalCount = toLong(row.get("total_count"));
        long nonNullCount = toLong(row.get("non_null_count"));
        long nullCount = totalCount - nonNullCount;
        return new TemporalStatistics(
            totalCount, nullCount, nonNullCount,
            Objects.toString(row.get("earliest"), null),
            Objects.toString(row.get("latest"), null)
        );
    }

    private BooleanStatistics mapBooleanStats(Map<String, Object> row) {
        long totalCount = toLong(row.get("total_count"));
        long nonNullCount = toLong(row.get("non_null_count"));
        long nullCount = totalCount - nonNullCount;
        long trueCount = toLong(row.get("true_count"));
        long falseCount = toLong(row.get("false_count"));
        return new BooleanStatistics(totalCount, nullCount, 2, trueCount, falseCount);
    }

    private CategoricalStatistics mapCategoricalStats(List<Map<String, Object>> rows) {
        long totalCount = 0;
        var topValues = new ArrayList<AttributeStatistics.ValueFrequency>();
        for (var row : rows) {
            long count = toLong(row.get("count"));
            totalCount += count;
            var value = Objects.toString(row.get("value"), "null");
            topValues.add(new AttributeStatistics.ValueFrequency(value, count));
        }
        return new CategoricalStatistics(totalCount, 0, rows.size(), List.copyOf(topValues));
    }

    private ReferenceStatistics mapReferenceStats(Attribute attribute, Map<String, Object> row) {
        String targetEntity = switch (attribute) {
            case SingularReferenceAttribute ref -> ref.targetRoot().primaryTableName();
            case PluralReferenceAttribute ref -> ref.targetRoot().primaryTableName();
            default -> "unknown";
        };
        long totalCount = toLong(row.get("total_count"));
        long linkedCount = toLong(row.getOrDefault("linked_count", 0));
        long nullCount = totalCount - linkedCount;
        return new ReferenceStatistics(totalCount, nullCount, linkedCount, targetEntity);
    }

    private BasicStatistics mapBasicStats(Map<String, Object> row) {
        long distinctCount = toLong(row.getOrDefault("distinct_count", 0));
        return new BasicStatistics(0, 0, distinctCount);
    }

    private static long estimateDistinctCount(long totalCount) {
        return totalCount;
    }

    private static BigDecimal toBigDecimal(@Nullable Object value) {
        return switch (value) {
            case null -> BigDecimal.ZERO;
            case BigDecimal bd -> bd;
            case Number n -> BigDecimal.valueOf(n.doubleValue());
            default -> new BigDecimal(value.toString());
        };
    }
}
