package com.rorm.dto;

import com.fasterxml.jackson.annotation.*;

import java.util.Set;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = SelectorDTO.RootSelectorDTO.class, name = "root"),
    @JsonSubTypes.Type(value = SelectorDTO.SingleExprSelectorDTO.class, name = "single"),
    @JsonSubTypes.Type(value = SelectorDTO.MultiExprSelectorDTO.class, name = "multi")
})
@JsonClassDescription("Defines what to SELECT in a query")
public sealed interface SelectorDTO permits
    SelectorDTO.RootSelectorDTO,
    SelectorDTO.SingleExprSelectorDTO,
    SelectorDTO.MultiExprSelectorDTO {

    @JsonPropertyDescription("Whether to apply DISTINCT to the selection.")
    @JsonProperty(required = true)
    boolean distinct();

    @JsonClassDescription("Select all basic attributes from the root entity (SELECT *)")
    record RootSelectorDTO(
        @JsonPropertyDescription("Name of the root entity whose attributes to select. Example: 'users', 'orders'")
        @JsonProperty(required = true)
        String rootName,

        @JsonPropertyDescription("Whether to select distinct rows.")
        @JsonProperty(required = true)
        boolean distinct
    ) implements SelectorDTO {}

    @JsonClassDescription("Select a single expression with optional alias")
    record SingleExprSelectorDTO(
        @JsonPropertyDescription("The expression to select.")
        @JsonProperty(required = true)
        ExpressionDTO expression,

        @JsonPropertyDescription("Whether to select distinct values.")
        @JsonProperty(required = true)
        boolean distinct,

        @JsonPropertyDescription("Optional alias for the selected expression (AS clause).")
        @JsonProperty(required = false)
        String alias
    ) implements SelectorDTO {}

    @JsonClassDescription("Select multiple expressions")
    record MultiExprSelectorDTO(
        @JsonPropertyDescription("Set of expressions to select, each with optional alias.")
        @JsonProperty(required = true)
        Set<SelectedExpressionDTO> expressions,

        @JsonPropertyDescription("Whether to select distinct rows.")
        @JsonProperty(required = true)
        boolean distinct
    ) implements SelectorDTO {}

    @JsonClassDescription("An expression with an optional alias for use in multi-expression selectors")
    record SelectedExpressionDTO(
        @JsonPropertyDescription("The expression to select.")
        @JsonProperty(required = true)
        ExpressionDTO expression,

        @JsonPropertyDescription("Optional alias for this expression.")
        @JsonProperty(required = false)
        String alias
    ) {}
}
