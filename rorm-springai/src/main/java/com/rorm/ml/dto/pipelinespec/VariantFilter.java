package com.rorm.ml.dto.pipelinespec;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.jspecify.annotations.Nullable;

import java.util.List;

@JsonNaming(SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonClassDescription("""
    Composable filter tree restricting a variant to a subpopulation. A node
    is either a leaf (column comparison) or a composite (AND / OR / NOT over
    sub-filters). Exactly one of the five field groups is populated per
    node: leaf fields (column + operator + values), an AND list, an OR list,
    or a NOT reference.""")
public record VariantFilter(

    @JsonProperty(required = false)
    @JsonPropertyDescription("""
        Leaf node: column to compare against. When set, operator and values
        must also be set, and the composite fields must be null.""")
    @Nullable String column,

    @JsonProperty(required = false)
    @JsonPropertyDescription("""
        Leaf node: comparison operator. IN matches any value in values;
        GT, LT, EQ compare against values[0].""")
    @Nullable FilterOperator operator,

    @JsonProperty(required = false)
    @JsonPropertyDescription("Leaf node: values used by the comparison operator. Required for all operators.")
    @Nullable List<Object> values,

    @JsonProperty(required = false)
    @JsonPropertyDescription("Composite node: conjunction of sub-filters (all must match).")
    @Nullable List<VariantFilter> and,

    @JsonProperty(required = false)
    @JsonPropertyDescription("Composite node: disjunction of sub-filters (any must match).")
    @Nullable List<VariantFilter> or,

    @JsonProperty(required = false)
    @JsonPropertyDescription("Composite node: negation of a single sub-filter.")
    @Nullable VariantFilter not
) {

    @JsonClassDescription("""
        Comparison operator for a leaf filter node. IN matches any value in
        values; GT, LT, EQ compare against values[0] (which must therefore
        be non-empty for those operators).""")
    public enum FilterOperator {
        IN, GT, LT, EQ
    }
}
