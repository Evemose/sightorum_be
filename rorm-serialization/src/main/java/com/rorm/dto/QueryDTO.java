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
    Long offset
) {

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

    @JsonClassDescription("ORDER BY clause item with expression and sort direction")
    public record OrderByDTO(
        @JsonPropertyDescription("Expression to order by.")
        @JsonProperty(required = true)
        ExpressionDTO expression,

        @JsonPropertyDescription("Sort direction: true for ASC, false for DESC.")
        @JsonProperty(required = true)
        boolean ascending
    ) {}

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
        List<OrderByDTO> orderBy
    ) {}
}
