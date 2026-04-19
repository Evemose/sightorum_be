package com.rorm.dto.dense;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.rorm.query.Join.JoinType;

import java.util.List;
import java.util.SequencedSet;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonClassDescription("""
    A database query with FROM, SELECT, WHERE, GROUP BY, ORDER BY, and pagination.
    
    Path strings are dot-separated (e.g., 'name', 'c.name', 'order.customer.address.city').
    First segment matches aliases (joins or FROM) or defaults to the FROM root's attributes.""")
public record DenseQueryDto(
    @JsonPropertyDescription("Root entity name (e.g., 'users', 'orders').")
    @JsonProperty(required = true)
    String from,

    @JsonPropertyDescription("Alias for the FROM table (e.g., 'c' for customers).")
    @JsonProperty(required = true)
    String fromAlias,

    @JsonPropertyDescription("SELECT clause definition.")
    @JsonProperty(required = true)
    DenseSelectorDto selector,

    @JsonProperty
    @JsonPropertyDescription("Explicit JOIN clauses.")
    SequencedSet<JoinDto> joins,

    @JsonProperty
    @JsonPropertyDescription("WHERE clause filter expression.")
    DenseExpressionDto where,

    @JsonProperty
    @JsonPropertyDescription("GROUP BY clause.")
    GroupByDto groupBy,

    @JsonProperty
    @JsonPropertyDescription("HAVING clause filter after grouping.")
    DenseExpressionDto having,

    @JsonProperty
    @JsonPropertyDescription("ORDER BY clause.")
    List<OrderByDto> orderBy,

    @JsonProperty
    @JsonPropertyDescription("Maximum rows to return (LIMIT).")
    Long limit,

    @JsonProperty
    @JsonPropertyDescription("Rows to skip (OFFSET).")
    Long offset,

    @JsonProperty
    @JsonPropertyDescription("Include tied rows at the limit boundary (WITH TIES). Requires ORDER BY.")
    Boolean withTies,

    @JsonProperty
    @JsonPropertyDescription("Common Table Expressions (WITH clause).")
    List<CteDto> ctes,

    @JsonProperty
    @JsonPropertyDescription("Set operations (UNION, INTERSECT, EXCEPT).")
    List<SetOperationDto> setOperations
) {

    /**
     * Backward-compatible constructor without withTies, ctes and setOperations.
     */
    public DenseQueryDto(
        String from, String fromAlias, DenseSelectorDto selector,
        SequencedSet<JoinDto> joins, DenseExpressionDto where, GroupByDto groupBy,
        DenseExpressionDto having, List<OrderByDto> orderBy, Long limit, Long offset
    ) {
        this(from, fromAlias, selector, joins, where, groupBy, having, orderBy, limit, offset, null, null, null);
    }

    /**
     * Backward-compatible constructor without withTies.
     */
    public DenseQueryDto(
        String from, String fromAlias, DenseSelectorDto selector,
        SequencedSet<JoinDto> joins, DenseExpressionDto where, GroupByDto groupBy,
        DenseExpressionDto having, List<OrderByDto> orderBy, Long limit, Long offset,
        List<CteDto> ctes, List<SetOperationDto> setOperations
    ) {
        this(from, fromAlias, selector, joins, where, groupBy, having, orderBy, limit, offset, null, ctes, setOperations);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GroupByDto(
        @JsonProperty(required = true)
        List<DenseExpressionDto> expressions
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OrderByDto(
        @JsonProperty(required = true)
        DenseExpressionDto expression,

        @JsonProperty(required = true)
        boolean ascending,

        @JsonPropertyDescription("Optional nulls placement: NULLS_FIRST or NULLS_LAST. Omit for database default.")
        String nullsHandling
    ) {
        /**
         * Backward-compatible constructor without nulls handling.
         */
        public OrderByDto(DenseExpressionDto expression, boolean ascending) {
            this(expression, ascending, null);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record JoinDto(
        @JsonProperty(required = true)
        JoinedRootDto joinedRoot,

        @JsonProperty(required = true)
        JoinType joinType,

        DenseExpressionDto onCondition
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record JoinedRootDto(
        @JsonProperty(required = true)
        String rootName,

        String alias
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WindowSpecDto(
        List<DenseExpressionDto> partitionBy,
        List<OrderByDto> orderBy,
        @JsonPropertyDescription("Window frame specification (ROWS/RANGE/GROUPS BETWEEN). Omit for default frame.")
        WindowFrameDto frame
    ) {
        /**
         * Backward-compatible constructor without frame.
         */
        public WindowSpecDto(List<DenseExpressionDto> partitionBy, List<OrderByDto> orderBy) {
            this(partitionBy, orderBy, null);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("Window frame: ROWS/RANGE/GROUPS BETWEEN start AND end.")
    public record WindowFrameDto(
        @JsonProperty(required = true)
        @JsonPropertyDescription("Frame unit: ROWS, RANGE, or GROUPS.")
        String type,

        @JsonProperty(required = true)
        @JsonPropertyDescription("Lower bound of the frame.")
        FrameBoundDto start,

        @JsonProperty(required = true)
        @JsonPropertyDescription("Upper bound of the frame.")
        FrameBoundDto end
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("Window frame endpoint.")
    public record FrameBoundDto(
        @JsonProperty(required = true)
        @JsonPropertyDescription("Bound type: UNBOUNDED_PRECEDING, N_PRECEDING, CURRENT_ROW, N_FOLLOWING, UNBOUNDED_FOLLOWING.")
        String type,

        @JsonPropertyDescription("Offset for N_PRECEDING / N_FOLLOWING. Required when type is N_PRECEDING or N_FOLLOWING.")
        Integer offset
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SelectedExpressionDto(
        @JsonProperty(required = true)
        DenseExpressionDto expression,

        String alias
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("A Common Table Expression (CTE) for WITH clause")
    public record CteDto(
        @JsonProperty(required = true)
        @JsonPropertyDescription("Name of the CTE.")
        String name,

        @JsonProperty(required = true)
        @JsonPropertyDescription("The CTE query definition.")
        DenseQueryDto query,

        @JsonPropertyDescription("Optional explicit column aliases.")
        List<String> columns
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("A set operation (UNION, INTERSECT, EXCEPT) with another query")
    public record SetOperationDto(
        @JsonProperty(required = true)
        @JsonPropertyDescription("Type: UNION, UNION_ALL, INTERSECT, INTERSECT_ALL, EXCEPT, EXCEPT_ALL")
        String type,

        @JsonProperty(required = true)
        @JsonPropertyDescription("The query to combine with.")
        DenseQueryDto query
    ) {}
}
