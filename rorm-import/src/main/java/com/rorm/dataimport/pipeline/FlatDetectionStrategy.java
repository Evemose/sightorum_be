package com.rorm.dataimport.pipeline;

import com.rorm.dataimport.attribute.AttributeTypeDetector;
import com.rorm.dataimport.attribute.DetectedAttribute;
import com.rorm.dataimport.hierarchical.HierarchicalDataSource;
import com.rorm.dataimport.naming.NamingStyle;
import com.rorm.dataimport.naming.NamingStyleDetector;
import com.rorm.dataimport.override.DetectionOverride;
import com.rorm.dataimport.override.SchemaOverride;
import com.rorm.dataimport.pipeline.SchemaDetector.DetectedIdColumn;
import com.rorm.dataimport.pipeline.SchemaDetector.DetectedRoot;
import com.rorm.dataimport.source.ImportDataSource;
import com.rorm.dataimport.type.DataTypeDetector;
import com.rorm.dataimport.type.InMemoryCoercion;
import com.rorm.metamodel.DataType;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Detection strategy for flat (CSV/TSV) data sources.
 * <p>
 * Uses column-based heuristics to detect schema structure including:
 * - Basic attributes from simple columns
 * - Composite attributes from prefixed columns
 * - References from *_id columns matching known roots
 * - OneToOneRoot from *_id columns with additional prefixed columns
 */
@RequiredArgsConstructor
public class FlatDetectionStrategy implements DetectionStrategy<ImportDataSource> {

    private final NamingStyleDetector namingStyleDetector;
    private final DataTypeDetector typeDetector;

    @Override
    public DetectedSchema detect(
        List<ImportDataSource> dataSources,
        Map<String, ? extends List<? extends DetectionOverride>> overridesByRoot,
        String defaultListSeparator,
        Set<String> allRootNames
    ) {
        var schemaOverridesByRoot = filterSchemaOverrides(overridesByRoot);

        // 1D: Extracted root detection
        var detectedRoots = dataSources.stream()
            .map(ds -> detectRoot(ds, schemaOverridesByRoot, defaultListSeparator, allRootNames))
            .collect(Collectors.toMap(DetectedRoot::name, root -> root));

        var allOverrides = schemaOverridesByRoot.values().stream()
            .flatMap(List::stream)
            .toList();
        var oneToOneRoots = collectAndCreateOneToOneRoots(detectedRoots, allOverrides);
        detectedRoots.putAll(oneToOneRoots);

        return new DetectedSchema(detectedRoots);
    }

    private DetectedRoot detectRoot(
        ImportDataSource dataSource,
        Map<String, List<SchemaOverride>> schemaOverridesByRoot,
        String defaultListSeparator,
        Set<String> allRootNames
    ) {
        var rootName = dataSource.getRootName();
        var columnDataTypes = detectColumnDataTypesForRoot(dataSource);
        var rootOverrides = schemaOverridesByRoot.getOrDefault(rootName, List.of());
        applyTypeOverridesRecursive(columnDataTypes, rootOverrides);
        var attributes = detectAttributesForRoot(dataSource, allRootNames, rootOverrides, defaultListSeparator, rootName, rootName);
        // 1C: Return-new-map enrichment
        var enrichedAttributes = enrichAttributesWithTypes(attributes, columnDataTypes);
        var idColumn = detectIdColumnForRoot(dataSource, rootOverrides, columnDataTypes);
        return new DetectedRoot(rootName, rootName, enrichedAttributes, idColumn);
    }

    @Override
    public boolean canHandle(ImportDataSource dataSource) {
        return !(dataSource instanceof HierarchicalDataSource);
    }

    private Map<String, DataType> detectColumnDataTypesForRoot(ImportDataSource dataSource) {
        var columnNames = dataSource.getColumnNames();
        var coercionStrategy = InMemoryCoercion.Skip.INSTANCE;

        var columnSamples = new HashMap<String, List<Object>>();
        for (var columnName : columnNames) {
            columnSamples.put(columnName, new ArrayList<>());
        }

        try (var stream = dataSource.stream()) {
            stream.limit(100).forEach(row -> {
                for (var columnName : columnNames) {
                    columnSamples.get(columnName).add(row.get(columnName));
                }
            });
        }

        var columnDataTypes = new HashMap<String, DataType>();
        for (var columnName : columnNames) {
            columnDataTypes.put(columnName, typeDetector.detectType(columnSamples.get(columnName), coercionStrategy));
        }
        return columnDataTypes;
    }

    // 1A: Consolidated type override application
    private void applyTypeOverridesRecursive(Map<String, DataType> columnDataTypes, List<SchemaOverride> overrides) {
        applyTypeOverrides(columnDataTypes, new ArrayList<>(columnDataTypes.keySet()), overrides, "");
    }

    private Map<String, List<SchemaOverride>> filterSchemaOverrides(
        Map<String, ? extends List<? extends DetectionOverride>> overridesByRoot
    ) {
        var result = new HashMap<String, List<SchemaOverride>>();
        for (var entry : overridesByRoot.entrySet()) {
            var schemaOverrides = entry.getValue().stream()
                .filter(o -> o instanceof SchemaOverride)
                .map(o -> (SchemaOverride) o)
                .toList();
            if (!schemaOverrides.isEmpty()) {
                result.put(entry.getKey(), schemaOverrides);
            }
        }
        return result;
    }

    private void applyTypeOverrides(
        Map<String, DataType> columnDataTypes,
        List<String> searchColumns,
        List<SchemaOverride> overrides,
        String parentPrefix
    ) {
        for (var override : overrides) {
            var action = resolveOverrideAction(override, searchColumns, parentPrefix);
            if (action == null) {
                continue;
            }
            switch (action) {
                case TypeOverrideAction.PutType put -> columnDataTypes.put(put.columnName(), put.dataType());
                case TypeOverrideAction.Recurse recurse ->
                    applyTypeOverrides(columnDataTypes, recurse.searchColumns(), recurse.nested(), recurse.nestedPrefix());
            }
        }
    }

    private @Nullable TypeOverrideAction resolveOverrideAction(
        SchemaOverride override,
        List<String> searchColumns,
        String parentPrefix
    ) {
        return switch (override) {
            case SchemaOverride.BasicAttributeOverride basic when basic.dataType() != null -> {
                var columnName = findColumnForAttribute(basic.attributeName(), searchColumns, parentPrefix)
                    .orElseThrow(() -> new IllegalArgumentException(
                        "Cannot find column for attribute override: " + basic.attributeName() +
                        (parentPrefix.isEmpty() ? "" : " under " + parentPrefix)
                    ));
                yield new TypeOverrideAction.PutType(columnName, Objects.requireNonNull(basic.dataType()));
            }
            case SchemaOverride.CollectionAttributeOverride coll when coll.elementType() != null -> {
                var columnName = findColumnForAttribute(coll.attributeName(), searchColumns, parentPrefix)
                    .orElseThrow(() -> new IllegalArgumentException(
                        "Cannot find column for collection attribute override: " + coll.attributeName() +
                        (parentPrefix.isEmpty() ? "" : " under " + parentPrefix)
                    ));
                yield new TypeOverrideAction.PutType(columnName, Objects.requireNonNull(coll.elementType()));
            }
            case SchemaOverride.CompositeAttributeOverride composite when !composite.nestedOverrides().isEmpty() -> {
                var namingStyle = namingStyleDetector.detect(composite.subAttributeColumns());
                var nestedPrefix = buildNestedPrefix(parentPrefix, composite.attributeName(), namingStyle);
                yield new TypeOverrideAction.Recurse(nestedPrefix, composite.subAttributeColumns(), composite.nestedOverrides());
            }
            case SchemaOverride.OneToOneRootOverride oneToOne when !oneToOne.nestedOverrides().isEmpty() -> {
                var namingStyle = namingStyleDetector.detect(oneToOne.subAttributeColumns());
                var nestedPrefix = buildNestedPrefix(parentPrefix, oneToOne.attributeName(), namingStyle);
                yield new TypeOverrideAction.Recurse(nestedPrefix, oneToOne.subAttributeColumns(), oneToOne.nestedOverrides());
            }
            default -> null;
        };
    }

    private String buildNestedPrefix(String parentPrefix, String attributeName, NamingStyle namingStyle) {
        var columnPrefix = convertAttributeNameToColumn(attributeName, namingStyle);
        return parentPrefix.isEmpty()
            ? columnPrefix
            : parentPrefix + namingStyle.getSeparator() + columnPrefix;
    }

    private Optional<String> findColumnForAttribute(
        String attributeName,
        List<String> searchColumns,
        String parentPrefix
    ) {
        var namingStyle = namingStyleDetector.detect(searchColumns);
        var columnPrefix = convertAttributeNameToColumn(attributeName, namingStyle);
        var expectedColumn = parentPrefix.isEmpty()
            ? columnPrefix
            : parentPrefix + namingStyle.getSeparator() + columnPrefix;

        if (searchColumns.contains(expectedColumn)) {
            return Optional.of(expectedColumn);
        }
        return searchColumns.stream()
            .filter(col -> col.equalsIgnoreCase(expectedColumn))
            .findFirst();
    }

    // 1C: Returns new map instead of mutating
    private Map<String, DetectedAttribute> enrichAttributesWithTypes(
        Map<String, DetectedAttribute> attributes,
        Map<String, DataType> columnDataTypes
    ) {
        var enriched = new HashMap<String, DetectedAttribute>();
        for (var entry : attributes.entrySet()) {
            enriched.put(entry.getKey(), enrichAttributeWithType(entry.getValue(), columnDataTypes));
        }
        return enriched;
    }

    private String convertAttributeNameToColumn(String attributeName, NamingStyle namingStyle) {
        var parts = attributeName.split("(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");
        return namingStyle.join(parts);
    }

    private DetectedAttribute enrichAttributeWithType(
        DetectedAttribute attr,
        Map<String, DataType> columnDataTypes
    ) {
        return switch (attr) {
            case DetectedAttribute.Basic basic -> new DetectedAttribute.Basic(
                basic.name(), basic.source(), columnDataTypes.get(basic.source().sourceColumn())
            );
            case DetectedAttribute.Collection coll -> new DetectedAttribute.Collection(
                coll.name(), coll.source(), coll.separator(), columnDataTypes.get(coll.source().sourceColumn())
            );
            case DetectedAttribute.SingularReference ref -> new DetectedAttribute.SingularReference(
                ref.name(), ref.source(), ref.targetRootName(), columnDataTypes.get(ref.source().sourceColumn())
            );
            case DetectedAttribute.PluralReference ref -> new DetectedAttribute.PluralReference(
                ref.name(), ref.source(), ref.targetRootName(), columnDataTypes.get(ref.source().sourceColumn())
            );
            case DetectedAttribute.Composite comp -> new DetectedAttribute.Composite(
                comp.name(), enrichAttributesWithTypes(comp.subAttributes(), columnDataTypes)
            );
            case DetectedAttribute.OneToOneRoot oneToOne -> new DetectedAttribute.OneToOneRoot(
                oneToOne.name(), oneToOne.targetRootName(),
                enrichAttributesWithTypes(oneToOne.subAttributes(), columnDataTypes)
            );
        };
    }

    private Map<String, DetectedAttribute> detectAttributesForRoot(
        ImportDataSource dataSource,
        Set<String> rootNames,
        List<SchemaOverride> overrides,
        String defaultListSeparator,
        String dataSourceName,
        String currentRootName
    ) {
        var columnNames = new ArrayList<>(dataSource.getColumnNames());

        overrides.stream()
            .filter(o -> o instanceof SchemaOverride.IdAttributeOverride)
            .map(o -> (SchemaOverride.IdAttributeOverride) o)
            .findFirst()
            .ifPresent(idOverride -> columnNames.remove(idOverride.columnName()));

        var namingStyle = namingStyleDetector.detect(columnNames);
        var detector = new AttributeTypeDetector(
            rootNames, namingStyle, overrides, defaultListSeparator, dataSourceName, currentRootName
        );
        return detector.detectAttributes(columnNames);
    }

    private Map<String, DetectedRoot> collectAndCreateOneToOneRoots(
        Map<String, DetectedRoot> detectedRoots,
        List<SchemaOverride> overrides
    ) {
        // 1E: Stream-based oneToOne collection
        return detectedRoots.values().stream()
            .flatMap(root -> collectOneToOneRoots(root.attributes().values(), root.sourceDataSource(), overrides))
            .collect(Collectors.toMap(
                DetectedRoot::name,
                root -> root,
                (existing, _) -> existing
            ));
    }

    // 1E: Returns Stream instead of mutable accumulation
    private Stream<DetectedRoot> collectOneToOneRoots(
        Collection<DetectedAttribute> attributes,
        String sourceDataSource,
        List<SchemaOverride> overrides
    ) {
        return attributes.stream().flatMap(attr -> switch (attr) {
            case DetectedAttribute.Composite composite ->
                collectOneToOneRoots(composite.subAttributes().values(), sourceDataSource, overrides);
            case DetectedAttribute.OneToOneRoot oneToOne -> {
                var overrideCandidate = findOneToOneIdColumnOverride(oneToOne.targetRootName(), overrides)
                    .map(column -> resolveOverrideIdCandidate(oneToOne.subAttributes(), column))
                    .orElse(null);
                var heuristicCandidate = resolveHeuristicIdCandidate(oneToOne.subAttributes());
                var idColumn = new IdCandidates(overrideCandidate, heuristicCandidate).resolve();
                yield Stream.of(new DetectedRoot(
                    oneToOne.targetRootName(), sourceDataSource, oneToOne.subAttributes(), idColumn
                ));
            }
            default -> Stream.empty();
        });
    }

    // 1F: Optional return type
    private Optional<String> findOneToOneIdColumnOverride(String targetRootName, List<SchemaOverride> overrides) {
        return overrides.stream()
            .map(override -> switch (override) {
                case SchemaOverride.OneToOneRootOverride oneToOne -> {
                    if (oneToOne.targetRootName().equals(targetRootName) && oneToOne.idColumn() != null) {
                        yield Optional.of(oneToOne.idColumn());
                    }
                    yield findOneToOneIdColumnOverride(targetRootName, oneToOne.nestedOverrides());
                }
                case SchemaOverride.CompositeAttributeOverride composite ->
                    findOneToOneIdColumnOverride(targetRootName, composite.nestedOverrides());
                default -> Optional.<String>empty();
            })
            .flatMap(Optional::stream)
            .findFirst();
    }

    // 1B: ID candidate resolution helpers
    private @Nullable DetectedIdColumn resolveOverrideIdCandidate(
        Map<String, DetectedAttribute> subAttributes, String overriddenIdColumn
    ) {
        return subAttributes.values().stream()
            .filter(attr -> attr instanceof DetectedAttribute.Basic)
            .map(attr -> (DetectedAttribute.Basic) attr)
            .filter(basic -> basic.source().sourceColumn().equalsIgnoreCase(overriddenIdColumn))
            .findFirst()
            .map(basic -> toIdColumn(basic.name(), basic.source().sourceColumn(), basic.dataType()))
            .orElse(null);
    }

    private @Nullable DetectedIdColumn resolveHeuristicIdCandidate(Map<String, DetectedAttribute> subAttributes) {
        return subAttributes.values().stream()
            .filter(attr -> attr instanceof DetectedAttribute.Basic)
            .map(attr -> (DetectedAttribute.Basic) attr)
            .filter(basic -> basic.source().sourceColumn().toLowerCase().endsWith("_id"))
            .findFirst()
            .map(basic -> toIdColumn(basic.name(), basic.source().sourceColumn(), basic.dataType()))
            .orElse(null);
    }

    private DetectedIdColumn toIdColumn(String name, String column, @Nullable DataType dataType) {
        return new DetectedIdColumn(name, column,
            Objects.requireNonNullElseGet(dataType, () -> new DataType.NumericType(19, 0)));
    }

    private DetectedIdColumn detectIdColumnForRoot(
        ImportDataSource dataSource,
        List<SchemaOverride> overrides,
        Map<String, DataType> columnDataTypes
    ) {
        var overrideCandidate = overrides.stream()
            .filter(o -> o instanceof SchemaOverride.IdAttributeOverride)
            .map(o -> (SchemaOverride.IdAttributeOverride) o)
            .findFirst()
            .map(override -> {
                var dataType = override.dataType() != null
                    ? override.dataType()
                    : columnDataTypes.getOrDefault(override.columnName(), new DataType.NumericType(19, 0));
                var naming = namingStyleDetector.detect(dataSource.getColumnNames());
                var attrName = Objects.requireNonNullElse(override.attributeName(), naming.forceAdjust(override.columnName()));
                return new DetectedIdColumn(attrName, override.columnName(), dataType);
            })
            .orElse(null);

        var heuristicCandidate = dataSource.getColumnNames().stream()
            .filter(c -> c.equalsIgnoreCase("id"))
            .findFirst()
            .map(idColumn -> new DetectedIdColumn("id", idColumn,
                columnDataTypes.getOrDefault(idColumn, new DataType.NumericType(19, 0))))
            .orElse(null);

        return new IdCandidates(overrideCandidate, heuristicCandidate).resolve();
    }

    // 1A: Sealed interface for type override dispatch
    private sealed interface TypeOverrideAction {
        record PutType(String columnName, DataType dataType) implements TypeOverrideAction {}

        record Recurse(String nestedPrefix, List<String> searchColumns,
                       List<SchemaOverride> nested) implements TypeOverrideAction {}
    }

    // 1B: Shared ID resolution
    private record IdCandidates(
        @Nullable DetectedIdColumn overrideCandidate,
        @Nullable DetectedIdColumn heuristicCandidate
    ) {
        DetectedIdColumn resolve() {
            if (overrideCandidate != null) {
                return overrideCandidate;
            }
            if (heuristicCandidate != null) {
                return heuristicCandidate;
            }
            return new DetectedIdColumn("id", "id", new DataType.NumericType(19, 0));
        }
    }
}
