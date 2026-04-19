package com.rorm.dto;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.rorm.query.Join.JoinType;

import java.util.List;
import java.util.SequencedSet;

@JsonClassDescription("""
    A database query specification with FROM, SELECT, WHERE, GROUP BY, ORDER BY, and pagination clauses.
    
    Path String Format:
    Paths are dot-separated strings that reference attributes through the metamodel (e.g., 'user.address.city').
    PathResolver follows these rules:
    1. First segment: Must be either an alias (from joins or FROM) OR an attribute name
       - If it matches an alias, it becomes the path starting point (AliasedRoot)
       - If no match, it's treated as an attribute of the implicit FROM root
    2. Subsequent segments: Must be attribute names, navigating through the metamodel
    3. Alias resolution priority:
       - Explicit join aliases (from 'joins' field)
       - FROM alias (always registered)
    4. Navigation rules:
       - From AliasedRoot: navigate to its attributes
       - From CompositeAttribute: navigate to nested attributes
       - From ReferenceAttribute: navigate to target root's attributes
       - From CompositeElement: navigate to its attributes
    
    Examples:
    - 'name' → attribute 'name' of implicit FROM root
    - 'c.name' → attribute 'name' of aliased root 'c' (where 'c' is FROM alias or a join alias)
    - 'address.city' → attribute 'city' of composite attribute 'address' on implicit FROM root
    - 'o.customer.name' → navigate from 'o' alias through reference 'customer' to attribute 'name'
    """)
public record QueryDTO(
    @JsonPropertyDescription("Name of the root entity to query from. This defines the primary table in the FROM clause. Example: 'users', 'orders'")
    @JsonProperty(required = true)
    String from,

    @JsonPropertyDescription("Alias for the FROM table. Allows referencing the main table by alias in expressions (e.g., 'c.name' where 'c' is the alias for customers).")
    @JsonProperty(required = true)
    String fromAlias,

    @JsonPropertyDescription("The selector defining what to retrieve (SELECT clause). Can select all attributes, specific expressions, or multiple expressions.")
    @JsonProperty(required = true)
    SelectorDTO selector,

    @JsonPropertyDescription("Explicit JOIN clauses. Joins to reference attributes are resolved automatically when referenced in expressions.")
    @JsonProperty(required = false)
    SequencedSet<JoinDTO> joins,

    @JsonPropertyDescription("WHERE clause filter expression. Combines conditions with AND/OR operators.")
    @JsonProperty(required = false)
    ExpressionDTO where,

    @JsonPropertyDescription("GROUP BY clause with list of expressions to group by.")
    @JsonProperty(required = false)
    GroupByDTO groupBy,

    @JsonPropertyDescription("HAVING clause filter expression applied after grouping.")
    @JsonProperty(required = false)
    ExpressionDTO having,

    @JsonPropertyDescription("ORDER BY clause with list of expressions and sort directions.")
    @JsonProperty(required = false)
    List<OrderByDTO> orderBy,

    @JsonPropertyDescription("Maximum number of rows to return (LIMIT clause).")
    @JsonProperty(required = false)
    Long limit,

    @JsonPropertyDescription("Number of rows to skip (OFFSET clause for pagination).")
    @JsonProperty(required = false)
    Long offset,

    @JsonPropertyDescription("Include tied rows at the limit boundary (FETCH FIRST ... WITH TIES). Requires ORDER BY clause.")
    @JsonProperty(required = false)
    Boolean withTies,

    @JsonPropertyDescription("Common Table Expressions (WITH clause). Each CTE defines a named subquery that can be referenced in the main query.")
    @JsonProperty(required = false)
    List<CteDTO> ctes,

    @JsonPropertyDescription("Set operations (UNION, INTERSECT, EXCEPT) to combine with other queries.")
    @JsonProperty(required = false)
    List<SetOperationDTO> setOperations
) {

    /**
     * Backward-compatible constructor without withTies, ctes and setOperations.
     */
    public QueryDTO(String from, String fromAlias, SelectorDTO selector,
                    SequencedSet<JoinDTO> joins, ExpressionDTO where, GroupByDTO groupBy,
                    ExpressionDTO having, List<OrderByDTO> orderBy, Long limit, Long offset) {
        this(from, fromAlias, selector, joins, where, groupBy, having, orderBy, limit, offset, null, null, null);
    }

    /**
     * Backward-compatible constructor without withTies (12 args, old canonical).
     */
    public QueryDTO(String from, String fromAlias, SelectorDTO selector,
                    SequencedSet<JoinDTO> joins, ExpressionDTO where, GroupByDTO groupBy,
                    ExpressionDTO having, List<OrderByDTO> orderBy, Long limit, Long offset,
                    List<CteDTO> ctes, List<SetOperationDTO> setOperations) {
        this(from, fromAlias, selector, joins, where, groupBy, having, orderBy, limit, offset, null, ctes, setOperations);
    }

    @JsonClassDescription("GROUP BY clause specification")
    public record GroupByDTO(
        @JsonPropertyDescription("List of expressions to group by.")
        @JsonProperty(required = true)
        List<ExpressionDTO> expressions
    ) {
        @JsonCreator
        public GroupByDTO {
        }
    }

    @JsonClassDescription("ORDER BY clause item with expression, sort direction, and optional nulls placement")
    public record OrderByDTO(
        @JsonPropertyDescription("Expression to order by.")
        @JsonProperty(required = true)
        ExpressionDTO expression,

        @JsonPropertyDescription("Sort direction: true for ASC, false for DESC.")
        @JsonProperty(required = true)
        boolean ascending,

        @JsonPropertyDescription("Optional nulls placement: NULLS_FIRST or NULLS_LAST. Omit for database default.")
        @JsonProperty(required = false)
        String nullsHandling
    ) {
        /**
         * Backward-compatible constructor without nulls handling.
         */
        public OrderByDTO(ExpressionDTO expression, boolean ascending) {
            this(expression, ascending, null);
        }
    }

    @JsonClassDescription("Explicit JOIN clause with a JoinedRoot")
    public record JoinDTO(
        @JsonPropertyDescription("The root table to join, with its alias.")
        @JsonProperty(required = true)
        JoinedRootDTO joinedRoot,

        @JsonPropertyDescription("Type of join: INNER, LEFT, RIGHT, CROSS")
        @JsonProperty(required = true)
        JoinType joinType,

        @JsonPropertyDescription("ON condition for the join. Required for INNER, LEFT, RIGHT joins, optional for CROSS joins.")
        @JsonProperty(required = false)
        ExpressionDTO onCondition
    ) {}

    @JsonClassDescription("A joined root with alias for explicit joins")
    public record JoinedRootDTO(
        @JsonPropertyDescription("Name of the root table to join. Example: 'orders', 'products'")
        @JsonProperty(required = true)
        String rootName,

        @JsonPropertyDescription("Alias for the joined table. Can be omitted, in which case it defaults to the root name.")
        @JsonProperty(required = false)
        String alias
    ) {}

    @JsonClassDescription("Window specification for window functions (OVER clause)")
    public record WindowSpecDTO(
        @JsonPropertyDescription("Expressions for PARTITION BY clause.")
        @JsonProperty(required = false)
        List<ExpressionDTO> partitionBy,

        @JsonPropertyDescription("Order specifications for ORDER BY within the window.")
        @JsonProperty(required = false)
        List<OrderByDTO> orderBy,

        @JsonPropertyDescription("Window frame specification (ROWS/RANGE/GROUPS BETWEEN).")
        @JsonProperty(required = false)
        WindowFrameDTO frame
    ) {
        /**
         * Backward-compatible constructor without frame.
         */
        public WindowSpecDTO(List<ExpressionDTO> partitionBy, List<OrderByDTO> orderBy) {
            this(partitionBy, orderBy, null);
        }
    }

    @JsonClassDescription("Window frame: ROWS/RANGE/GROUPS BETWEEN start AND end.")
    public record WindowFrameDTO(
        @JsonPropertyDescription("Frame unit: ROWS, RANGE, or GROUPS.")
        @JsonProperty(required = true)
        String type,

        @JsonPropertyDescription("Lower bound of the frame.")
        @JsonProperty(required = true)
        FrameBoundDTO start,

        @JsonPropertyDescription("Upper bound of the frame.")
        @JsonProperty(required = true)
        FrameBoundDTO end
    ) {}

    @JsonClassDescription("Window frame endpoint.")
    public record FrameBoundDTO(
        @JsonPropertyDescription("Bound type: UNBOUNDED_PRECEDING, N_PRECEDING, CURRENT_ROW, N_FOLLOWING, UNBOUNDED_FOLLOWING.")
        @JsonProperty(required = true)
        String type,

        @JsonPropertyDescription("Offset for N_PRECEDING / N_FOLLOWING.")
        @JsonProperty(required = false)
        Integer offset
    ) {}

    @JsonClassDescription("A Common Table Expression (CTE) for WITH clause")
    public record CteDTO(
        @JsonPropertyDescription("Name of the CTE, used as a table reference in the main query.")
        @JsonProperty(required = true)
        String name,

        @JsonPropertyDescription("The CTE query definition.")
        @JsonProperty(required = true)
        QueryDTO query,

        @JsonPropertyDescription("Optional explicit column aliases for the CTE.")
        @JsonProperty(required = false)
        List<String> columns
    ) {}

    @JsonClassDescription("A set operation (UNION, INTERSECT, EXCEPT) with another query")
    public record SetOperationDTO(
        @JsonPropertyDescription("Type: UNION, UNION_ALL, INTERSECT, INTERSECT_ALL, EXCEPT, EXCEPT_ALL")
        @JsonProperty(required = true)
        String type,

        @JsonPropertyDescription("The query to combine with.")
        @JsonProperty(required = true)
        QueryDTO query
    ) {}
}
