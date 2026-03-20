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
    Long offset
) {

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
        boolean ascending
    ) {}

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
        List<OrderByDto> orderBy
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SelectedExpressionDto(
        @JsonProperty(required = true)
        DenseExpressionDto expression,

        String alias
    ) {}
}
