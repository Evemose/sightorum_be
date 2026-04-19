package com.rorm.ai.tools;

import com.rorm.dto.dense.DenseExpressionDto;
import com.rorm.dto.dense.DenseQueryDto;
import com.rorm.dto.dense.DenseQueryDto.OrderByDto;
import com.rorm.dto.dense.DenseQueryDto.SelectedExpressionDto;
import com.rorm.dto.dense.DenseSelectorDto;
import org.jspecify.annotations.Nullable;

import java.util.*;

final class DescriptiveQueryBuilder {

    private DescriptiveQueryBuilder() {
    }

    static DenseQueryDto plainQuery(String rootName, @Nullable DenseExpressionDto filter,
                                    SelectedExpressionDto... selections) {
        return new DenseQueryDto(rootName, "t",
            DenseSelectorDto.multi(new LinkedHashSet<>(List.of(selections)), false),
            null, normalizeFilter(filter), null, null, null, null, null);
    }

    private static @Nullable DenseExpressionDto normalizeFilter(@Nullable DenseExpressionDto filter) {
        if (filter == null) {
            return null;
        }
        if ("literal".equals(filter.type())) {
            return null;
        }
        if ("path".equals(filter.type())) {
            return DenseExpressionDto.unary("IS_TRUE", filter);
        }
        return filter;
    }

    static DenseQueryDto groupedQuery(String rootName, DenseExpressionDto groupByExpr,
                                      @Nullable DenseExpressionDto filter,
                                      SelectedExpressionDto... aggregations) {
        var selections = new LinkedHashSet<SelectedExpressionDto>();
        selections.add(new SelectedExpressionDto(groupByExpr, "category"));
        Collections.addAll(selections, aggregations);
        return new DenseQueryDto(rootName, "t",
            DenseSelectorDto.multi(selections, false),
            null, normalizeFilter(filter), new DenseQueryDto.GroupByDto(List.of(groupByExpr)),
            null, null, null, null);
    }

    static DenseQueryDto rankedQuery(String rootName, DenseExpressionDto groupByExpr,
                                     @Nullable DenseExpressionDto filter,
                                     SelectedExpressionDto measureAgg,
                                     boolean descending, @Nullable Long limit) {
        var selections = new LinkedHashSet<SelectedExpressionDto>();
        selections.add(new SelectedExpressionDto(groupByExpr, "rank_key"));
        selections.add(measureAgg);
        selections.add(countStar("n"));
        var orderBy = List.of(new OrderByDto(measureAgg.expression(), !descending));
        return new DenseQueryDto(rootName, "t",
            DenseSelectorDto.multi(selections, false),
            null, normalizeFilter(filter), new DenseQueryDto.GroupByDto(List.of(groupByExpr)),
            null, orderBy, limit, null);
    }

    static SelectedExpressionDto countStar(String alias) {
        return new SelectedExpressionDto(
            DenseExpressionDto.aggregation("COUNT", List.of(), false), alias);
    }

    static DenseQueryDto bucketedQuery(String rootName, DenseExpressionDto bucketExpr,
                                       @Nullable DenseExpressionDto filter,
                                       SelectedExpressionDto aggregation) {
        var selections = new LinkedHashSet<SelectedExpressionDto>();
        selections.add(new SelectedExpressionDto(bucketExpr, "bucket"));
        selections.add(aggregation);
        var orderBy = List.of(new OrderByDto(bucketExpr, true));
        return new DenseQueryDto(rootName, "t",
            DenseSelectorDto.multi(selections, false),
            null, normalizeFilter(filter), new DenseQueryDto.GroupByDto(List.of(bucketExpr)),
            null, orderBy, null, null);
    }

    static DenseQueryDto bucketedByAxisQuery(String rootName, DenseExpressionDto bucketExpr,
                                             DenseExpressionDto axisExpr,
                                             @Nullable DenseExpressionDto filter,
                                             SelectedExpressionDto aggregation) {
        var selections = new LinkedHashSet<SelectedExpressionDto>();
        selections.add(new SelectedExpressionDto(bucketExpr, "bucket"));
        selections.add(new SelectedExpressionDto(axisExpr, "segment"));
        selections.add(aggregation);
        var orderBy = List.of(
            new OrderByDto(bucketExpr, true),
            new OrderByDto(axisExpr, true));
        return new DenseQueryDto(rootName, "t",
            DenseSelectorDto.multi(selections, false),
            null, normalizeFilter(filter),
            new DenseQueryDto.GroupByDto(List.of(bucketExpr, axisExpr)),
            null, orderBy, null, null);
    }

    static DenseExpressionDto withTimeFilter(@Nullable DenseExpressionDto filter,
                                             @Nullable DenseExpressionDto timeExpression,
                                             @Nullable String timeStart,
                                             @Nullable String timeEnd) {
        if (timeExpression == null || (timeStart == null && timeEnd == null)) {
            return filter == null ? DenseExpressionDto.literal(true) : filter;
        }
        var clauses = new ArrayList<DenseExpressionDto>();
        if (filter != null && !(filter.type().equals("literal") && filter.value() == null)) {
            clauses.add(filter);
        }
        if (timeStart != null) {
            clauses.add(DenseExpressionDto.binary(
                timeExpression, "GREATER_THAN_OR_EQUAL", DenseExpressionDto.literal(timeStart)));
        }
        if (timeEnd != null) {
            clauses.add(DenseExpressionDto.binary(
                timeExpression, "LESS_THAN", DenseExpressionDto.literal(timeEnd)));
        }
        return andAll(clauses);
    }

    static DenseExpressionDto andAll(List<DenseExpressionDto> clauses) {
        if (clauses.isEmpty()) {
            return null;
        }
        if (clauses.size() == 1) {
            return clauses.getFirst();
        }
        var result = clauses.getFirst();
        for (var i = 1; i < clauses.size(); i++) {
            result = DenseExpressionDto.binary(result, "AND", clauses.get(i));
        }
        return result;
    }

    static DenseExpressionDto between(DenseExpressionDto value, DenseExpressionDto low, DenseExpressionDto high) {
        return DenseExpressionDto.ternary(value, "BETWEEN", low, high);
    }

    static DenseExpressionDto dateTruncBucket(String grain, DenseExpressionDto timeExpression) {
        return DenseExpressionDto.functionCall("DATE_TRUNC",
            List.of(DenseExpressionDto.literal(grain), timeExpression));
    }

    static SelectedExpressionDto percentileCont(double fraction, DenseExpressionDto orderExpression, String alias) {
        var agg = DenseExpressionDto.aggregation("PERCENTILE_CONT",
            List.of(DenseExpressionDto.literal(fraction), orderExpression), false);
        return new SelectedExpressionDto(agg, alias);
    }

    static SelectedExpressionDto sumOf(DenseExpressionDto expr, String alias) {
        return new SelectedExpressionDto(
            DenseExpressionDto.aggregation("SUM", List.of(expr), false), alias);
    }

    static SelectedExpressionDto avgOf(DenseExpressionDto expr, String alias) {
        return new SelectedExpressionDto(
            DenseExpressionDto.aggregation("AVG", List.of(expr), false), alias);
    }

    static SelectedExpressionDto stddevOf(DenseExpressionDto expr, String alias) {
        return new SelectedExpressionDto(
            DenseExpressionDto.aggregation("STDDEV_POP", List.of(expr), false), alias);
    }

    static SelectedExpressionDto minOf(DenseExpressionDto expr, String alias) {
        return new SelectedExpressionDto(
            DenseExpressionDto.aggregation("MIN", List.of(expr), false), alias);
    }

    static SelectedExpressionDto maxOf(DenseExpressionDto expr, String alias) {
        return new SelectedExpressionDto(
            DenseExpressionDto.aggregation("MAX", List.of(expr), false), alias);
    }

    static SelectedExpressionDto ratioOf(DenseExpressionDto numerator, DenseExpressionDto denominator, String alias) {
        var numSum = DenseExpressionDto.aggregation("SUM", List.of(numerator), false);
        var denSum = DenseExpressionDto.aggregation("SUM", List.of(denominator), false);
        return new SelectedExpressionDto(DenseExpressionDto.binary(numSum, "DIVIDE", denSum), alias);
    }

    static SelectedExpressionDto measureSelector(DenseExpressionDto measure, String kind,
                                                 @Nullable DenseExpressionDto denominator, String alias) {
        return new SelectedExpressionDto(measureSelectorExpression(measure, kind, denominator), alias);
    }

    static DenseExpressionDto measureSelectorExpression(DenseExpressionDto measure, String kind,
                                                        @Nullable DenseExpressionDto denominator) {
        return switch (kind.toLowerCase()) {
            case "total", "sum" -> DenseExpressionDto.aggregation("SUM", List.of(measure), false);
            case "mean", "avg", "average" -> DenseExpressionDto.aggregation("AVG", List.of(measure), false);
            case "count" -> DenseExpressionDto.aggregation("COUNT", List.of(measure), false);
            case "median" -> DenseExpressionDto.aggregation("PERCENTILE_CONT",
                List.of(DenseExpressionDto.literal(0.5), measure), false);
            case "ratio" -> {
                if (denominator == null) {
                    throw new IllegalArgumentException("kind=ratio requires a denominator expression");
                }
                yield DenseExpressionDto.binary(
                    DenseExpressionDto.aggregation("SUM", List.of(measure), false),
                    "DIVIDE",
                    DenseExpressionDto.aggregation("SUM", List.of(denominator), false));
            }
            default -> throw new IllegalArgumentException("Unknown measure kind: " + kind);
        };
    }

    static Set<String> knownGrains() {
        return Set.of("day", "week", "month", "quarter", "year");
    }
}
