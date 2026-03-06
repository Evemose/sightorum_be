package com.rorm.ai;

import com.rorm.engine.ExpressionTypeResolver;
import com.rorm.engine.TypeCategory;
import com.rorm.metamodel.*;
import com.rorm.query.*;
import com.rorm.query.Expression.*;
import com.rorm.query.Selector.MultiExprSelector;
import com.rorm.query.Selector.SingleExprSelector;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Service for generating data overview queries.
 * <p>
 * This service builds Query objects for various statistical and analytical operations
 * without executing them. Query execution is the responsibility of the caller.
 */
@Component
@RequiredArgsConstructor
public class DataOverviewService {

    private final ExpressionTypeResolver typeResolver;

    /**
     * Generates a query for numeric statistics: COUNT, MIN, MAX, AVG, SUM, STDDEV, VARIANCE.
     * <p>
     * The result includes:
     * <ul>
     *   <li>total_count - total number of rows</li>
     *   <li>non_null_count - number of non-null values</li>
     *   <li>null_count - number of null values</li>
     *   <li>min - minimum value</li>
     *   <li>max - maximum value</li>
     *   <li>avg - average value</li>
     *   <li>sum - sum of values</li>
     *   <li>stddev - population standard deviation</li>
     *   <li>variance - population variance</li>
     * </ul>
     *
     * @param fromRoot   the root entity to query
     * @param expression the numeric expression to analyze
     * @return a Query that produces numeric statistics
     * @throws IllegalArgumentException if expression is not numeric
     */
    public Query buildNumericStatsQuery(Root fromRoot, Expression expression) {
        validateNumericExpression(expression, fromRoot);

        return Query.builder()
            .from(AliasedRoot.of(fromRoot))
            .selector(new MultiExprSelector(Set.of(
                new SelectedExpression(agg(StandardAggregation.COUNT.identifier(), literal("*")), "total_count"),
                new SelectedExpression(agg(StandardAggregation.COUNT.identifier(), expression), "non_null_count"),
                new SelectedExpression(
                    binary(
                        agg(StandardAggregation.COUNT.identifier(), literal("*")),
                        StandardOperator.Binary.SUBTRACT.identifier(),
                        agg(StandardAggregation.COUNT.identifier(), expression)
                    ), "null_count"
                ),
                new SelectedExpression(agg(StandardAggregation.MIN.identifier(), expression), "min"),
                new SelectedExpression(agg(StandardAggregation.MAX.identifier(), expression), "max"),
                new SelectedExpression(agg(StandardAggregation.AVG.identifier(), expression), "avg"),
                new SelectedExpression(agg(StandardAggregation.SUM.identifier(), expression), "sum"),
                new SelectedExpression(func(StandardAggregation.STDDEV_POP.identifier(), expression), "stddev"),
                new SelectedExpression(func(StandardAggregation.VAR_POP.identifier(), expression), "variance")
            ), false))
            .build();
    }

    private void validateNumericExpression(Expression expression, Root fromRoot) {
        var type = typeResolver.resolveWithRoot(expression, fromRoot);
        if (!(type instanceof DataType.NumericType)) {
            throw new IllegalArgumentException(
                "Expression must be numeric for this operation. Got: " + type);
        }
    }

    private static Aggregation agg(String name, Expression arg) {
        return new Aggregation(name, List.of(arg), false);
    }

    private static Literal literal(Object value) {
        return new Literal(value);
    }

    private static BinaryExpression binary(Expression left, String op, Expression right) {
        return new BinaryExpression(left, op, right);
    }

    private static FunctionCall func(String name, Expression... args) {
        return new FunctionCall(name, List.of(args));
    }

    /**
     * Generates a query for numeric histogram with configurable bins.
     * <p>
     * Uses WIDTH_BUCKET for PostgreSQL-compatible histogram binning.
     * Returns bucket number, count, and bucket boundaries.
     *
     * @param fromRoot   the root entity to query
     * @param expression the numeric expression to analyze
     * @param binCount   number of histogram bins
     * @return a Query that produces histogram data
     * @throws IllegalArgumentException if expression is not numeric or binCount &lt; 1
     */
    public Query buildNumericHistogramQuery(Root fromRoot, Expression expression, int binCount) {
        validateNumericExpression(expression, fromRoot);
        if (binCount < 1) {
            throw new IllegalArgumentException("Bin count must be at least 1, got: " + binCount);
        }

        // Subqueries for min and max
        var minSubquery = new Subquery(Query.builder()
            .from(AliasedRoot.of(fromRoot))
            .selector(new SingleExprSelector(agg("MIN", expression), false, "min_val"))
            .build());

        var maxSubquery = new Subquery(Query.builder()
            .from(AliasedRoot.of(fromRoot))
            .selector(new SingleExprSelector(agg("MAX", expression), false, "max_val"))
            .build());

        // Add small epsilon to max to include the max value in the last bucket
        var maxPlusEpsilon = binary(maxSubquery, StandardOperator.Binary.ADD.identifier(), literal(0.0001));

        // WIDTH_BUCKET(value, min, max + epsilon, num_buckets)
        var bucketFunc = func("WIDTH_BUCKET", expression, minSubquery, maxPlusEpsilon, literal(binCount));

        return Query.builder()
            .from(AliasedRoot.of(fromRoot))
            .selector(new MultiExprSelector(Set.of(
                new SelectedExpression(bucketFunc, "bucket"),
                new SelectedExpression(agg("COUNT", literal("*")), "count"),
                new SelectedExpression(agg("MIN", expression), "bucket_min"),
                new SelectedExpression(agg("MAX", expression), "bucket_max")
            ), false))
            .where(unary(StandardOperator.Unary.IS_NOT_NULL.identifier(), expression))
            .groupBy(new GroupBy(bucketFunc))
            .orderBy(OrderBy.asc(bucketFunc))
            .build();
    }

    private static UnaryExpression unary(String op, Expression operand) {
        return new UnaryExpression(op, operand);
    }

    /**
     * Generates a query for categorical frequency distribution.
     * <p>
     * Returns value counts ordered by frequency (descending).
     *
     * @param fromRoot     the root entity to query
     * @param expression   the expression to analyze
     * @param limit        maximum number of values to return (null for all)
     * @param includeNulls whether to include null values in the result
     * @return a Query that produces frequency counts
     */
    public Query buildCategoricalFrequencyQuery(Root fromRoot, Expression expression,
                                                @Nullable Integer limit, boolean includeNulls) {
        var countExpr = agg("COUNT", literal("*"));

        var builder = Query.builder()
            .from(AliasedRoot.of(fromRoot))
            .selector(new MultiExprSelector(Set.of(
                new SelectedExpression(expression, "value"),
                new SelectedExpression(countExpr, "count")
            ), false))
            .groupBy(new GroupBy(expression))
            .orderBy(OrderBy.desc(countExpr));

        if (!includeNulls) {
            builder.where(unary(StandardOperator.Unary.IS_NOT_NULL.identifier(), expression));
        }

        if (limit != null && limit > 0) {
            builder.limit((long) limit);
        }

        return builder.build();
    }

    /**
     * Generates a query for distinct value count.
     *
     * @param fromRoot   the root entity to query
     * @param expression the expression to count distinct values of
     * @return a Query that returns the distinct count
     */
    public Query buildDistinctCountQuery(Root fromRoot, Expression expression) {
        return Query.builder()
            .from(AliasedRoot.of(fromRoot))
            .selector(new SingleExprSelector(
                new Aggregation("COUNT", List.of(expression), true),
                false,
                "distinct_count"
            ))
            .build();
    }

    /**
     * Generates a query to count null values.
     *
     * @param fromRoot   the root entity to query
     * @param expression the expression to check for nulls
     * @return a Query that returns the null count
     */
    public Query buildNullCountQuery(Root fromRoot, Expression expression) {
        return Query.builder()
            .from(AliasedRoot.of(fromRoot))
            .selector(new SingleExprSelector(agg("COUNT", literal("*")), false, "null_count"))
            .where(unary(StandardOperator.Unary.IS_NULL.identifier(), expression))
            .build();
    }

    /**
     * Generates a query for singular reference population statistics.
     * <p>
     * Returns total count and count of non-null foreign key values.
     *
     * @param fromRoot      the root entity to query
     * @param referencePath path to a singular reference attribute
     * @return a Query that returns reference population stats
     * @throws IllegalArgumentException if path is not a reference
     */
    public Query buildSingularReferenceCountQuery(Root fromRoot, Path referencePath) {
        validateReferenceExpression(referencePath);

        return Query.builder()
            .from(AliasedRoot.of(fromRoot))
            .selector(new MultiExprSelector(Set.of(
                new SelectedExpression(agg("COUNT", literal("*")), "total_count"),
                new SelectedExpression(agg("COUNT", referencePath), "linked_count"),
                new SelectedExpression(
                    binary(agg("COUNT", literal("*")), StandardOperator.Binary.SUBTRACT.identifier(), agg("COUNT", referencePath)),
                    "unlinked_count"
                )
            ), false))
            .build();
    }

    private void validateReferenceExpression(Path path) {
        if (!(path.target() instanceof ReferenceAttribute)) {
            throw new IllegalArgumentException(
                "Path must target a ReferenceAttribute. Got: " + path.target().getClass().getSimpleName());
        }
    }

    // Validation helpers

    /**
     * Generates a query for plural reference cardinality per parent.
     * <p>
     * Returns the count of related records for each parent entity.
     *
     * @param fromRoot      the root entity to query
     * @param referencePath path to a plural reference attribute
     * @return a Query that returns child count per parent
     * @throws IllegalArgumentException if path is not a plural reference
     */
    public Query buildPluralReferenceStatsQuery(Root fromRoot, Path referencePath) {
        validatePluralReferenceExpression(referencePath);

        var pluralRef = (PluralReferenceAttribute) referencePath.target();
        var targetRoot = pluralRef.targetRoot();
        var targetIdPath = new Path(targetRoot.idDescriptor().idAttribute(), referencePath);

        var sourceIdAttr = fromRoot.idDescriptor().idAttribute();
        var sourceIdPath = new Path(sourceIdAttr, null);

        return Query.builder()
            .from(AliasedRoot.of(fromRoot))
            .selector(new MultiExprSelector(Set.of(
                new SelectedExpression(sourceIdPath, "parent_id"),
                new SelectedExpression(agg("COUNT", targetIdPath), "child_count")
            ), false))
            .groupBy(new GroupBy(sourceIdPath))
            .orderBy(OrderBy.desc(agg("COUNT", targetIdPath)))
            .build();
    }

    private void validatePluralReferenceExpression(Path path) {
        if (!(path.target() instanceof PluralReferenceAttribute)) {
            throw new IllegalArgumentException(
                "Path must target a PluralReferenceAttribute. Got: " + path.target().getClass().getSimpleName());
        }
    }

    /**
     * Generates a query for summary statistics of plural reference cardinalities.
     * <p>
     * Returns aggregate stats (min, max, avg, etc.) across all parent-child relationships.
     *
     * @param fromRoot      the root entity to query
     * @param referencePath path to a plural reference attribute
     * @return a Query that returns summary stats of child counts
     */
    public Query buildPluralReferenceSummaryQuery(Root fromRoot, Path referencePath) {
        validatePluralReferenceExpression(referencePath);

        var pluralRef = (PluralReferenceAttribute) referencePath.target();
        var targetRoot = pluralRef.targetRoot();
        var targetIdPath = new Path(targetRoot.idDescriptor().idAttribute(), referencePath);

        var sourceIdAttr = fromRoot.idDescriptor().idAttribute();
        var sourceIdPath = new Path(sourceIdAttr, null);

        // Count per parent
        var childCountExpr = agg("COUNT", targetIdPath);

        return Query.builder()
            .from(AliasedRoot.of(fromRoot))
            .selector(new MultiExprSelector(Set.of(
                new SelectedExpression(agg("COUNT", literal("*")), "parent_count"),
                new SelectedExpression(agg("SUM", childCountExpr), "total_children"),
                new SelectedExpression(agg("AVG", childCountExpr), "avg_children"),
                new SelectedExpression(agg("MIN", childCountExpr), "min_children"),
                new SelectedExpression(agg("MAX", childCountExpr), "max_children")
            ), false))
            .groupBy(new GroupBy(sourceIdPath))
            .build();
    }

    /**
     * Generates a query for temporal distribution by specified granularity.
     * <p>
     * Groups timestamps by the specified time period and counts occurrences.
     *
     * @param fromRoot    the root entity to query
     * @param expression  the temporal expression to analyze
     * @param granularity the time granularity for grouping
     * @return a Query that returns temporal distribution
     * @throws IllegalArgumentException if expression is not temporal
     */
    public Query buildTemporalDistributionQuery(Root fromRoot, Expression expression,
                                                TemporalGranularity granularity) {
        validateTemporalExpression(expression, fromRoot);

        var truncFunc = func("DATE_TRUNC", literal(granularity.getSqlLiteral()), expression);

        return Query.builder()
            .from(AliasedRoot.of(fromRoot))
            .selector(new MultiExprSelector(Set.of(
                new SelectedExpression(truncFunc, "period"),
                new SelectedExpression(agg("COUNT", literal("*")), "count")
            ), false))
            .where(unary(StandardOperator.Unary.IS_NOT_NULL.identifier(), expression))
            .groupBy(new GroupBy(truncFunc))
            .orderBy(OrderBy.asc(truncFunc))
            .build();
    }

    private void validateTemporalExpression(Expression expression, Root fromRoot) {
        var category = typeResolver.categorize(expression, fromRoot);
        if (category != TypeCategory.TEMPORAL) {
            throw new IllegalArgumentException(
                "Expression must be temporal for this operation. Got category: " + category);
        }
    }

    // Expression building helpers

    /**
     * Generates a query for temporal range statistics.
     * <p>
     * Returns min, max, and total count for temporal data.
     *
     * @param fromRoot   the root entity to query
     * @param expression the temporal expression to analyze
     * @return a Query that returns temporal range stats
     */
    public Query buildTemporalRangeQuery(Root fromRoot, Expression expression) {
        validateTemporalExpression(expression, fromRoot);

        return Query.builder()
            .from(AliasedRoot.of(fromRoot))
            .selector(new MultiExprSelector(Set.of(
                new SelectedExpression(agg("COUNT", literal("*")), "total_count"),
                new SelectedExpression(agg("COUNT", expression), "non_null_count"),
                new SelectedExpression(agg("MIN", expression), "earliest"),
                new SelectedExpression(agg("MAX", expression), "latest")
            ), false))
            .build();
    }

    /**
     * Generates a query for boolean distribution.
     * <p>
     * Returns counts of true, false, and null values.
     *
     * @param fromRoot   the root entity to query
     * @param expression the boolean expression to analyze
     * @return a Query that returns boolean distribution
     */
    public Query buildBooleanDistributionQuery(Root fromRoot, Expression expression) {
        validateBooleanExpression(expression, fromRoot);

        // Use CASE expressions to count true/false
        var trueCount = agg("SUM", func("CASE",
            expression,
            literal(1),
            literal(0)));

        var falseCount = agg("SUM", func("CASE",
            unary(StandardOperator.Unary.NOT.identifier(), expression),
            literal(1),
            literal(0)));

        return Query.builder()
            .from(AliasedRoot.of(fromRoot))
            .selector(new MultiExprSelector(Set.of(
                new SelectedExpression(agg("COUNT", literal("*")), "total_count"),
                new SelectedExpression(agg("COUNT", expression), "non_null_count"),
                new SelectedExpression(trueCount, "true_count"),
                new SelectedExpression(falseCount, "false_count")
            ), false))
            .build();
    }

    private void validateBooleanExpression(Expression expression, Root fromRoot) {
        var type = typeResolver.resolveWithRoot(expression, fromRoot);
        if (!(type instanceof DataType.BooleanType)) {
            throw new IllegalArgumentException(
                "Expression must be boolean for this operation. Got: " + type);
        }
    }

    /**
     * Generates a query for percentile statistics.
     * <p>
     * Returns common percentiles (25th, 50th/median, 75th, 90th, 95th, 99th).
     *
     * @param fromRoot   the root entity to query
     * @param expression the numeric expression to analyze
     * @return a Query that returns percentile values
     */
    public Query buildPercentilesQuery(Root fromRoot, Expression expression) {
        validateNumericExpression(expression, fromRoot);

        return Query.builder()
            .from(AliasedRoot.of(fromRoot))
            .selector(new MultiExprSelector(Set.of(
                new SelectedExpression(func("PERCENTILE_CONT", literal(0.25), expression), "p25"),
                new SelectedExpression(func("PERCENTILE_CONT", literal(0.50), expression), "p50_median"),
                new SelectedExpression(func("PERCENTILE_CONT", literal(0.75), expression), "p75"),
                new SelectedExpression(func("PERCENTILE_CONT", literal(0.90), expression), "p90"),
                new SelectedExpression(func("PERCENTILE_CONT", literal(0.95), expression), "p95"),
                new SelectedExpression(func("PERCENTILE_CONT", literal(0.99), expression), "p99")
            ), false))
            .build();
    }

    /**
     * Temporal granularity options for distribution queries.
     */
    @Getter
    public enum TemporalGranularity {
        YEAR("year"),
        QUARTER("quarter"),
        MONTH("month"),
        WEEK("week"),
        DAY("day"),
        HOUR("hour"),
        MINUTE("minute");

        private final String sqlLiteral;

        TemporalGranularity(String sqlLiteral) {
            this.sqlLiteral = sqlLiteral;
        }
    }
}
