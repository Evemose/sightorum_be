package com.rorm.ai.swarm.dto;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

@JsonClassDescription("Variable produced by a research step")
public record ResearchVariable(
    @JsonPropertyDescription("Unique variable name (e.g., 'avg_order_value_by_segment')")
    @JsonProperty(required = true)
    String variableName,

    @JsonPropertyDescription("""
        What this variable represents.
        Example: "Average order value grouped by customer segment"
        """)
    @JsonProperty(required = true)
    String description,

    @JsonPropertyDescription("""
        Data type: NUMBER (single value), LIST (array), TABLE (structured dataset),
        BOOLEAN (true/false), TEXT (string), DISTRIBUTION (histogram/percentiles)
        """)
    @JsonProperty(required = true)
    VariableType type,

    @JsonPropertyDescription("Which step produces this variable")
    @JsonProperty(required = true)
    StepRef producedBy
) {
    public enum VariableType {
        NUMBER, LIST, TABLE, BOOLEAN, TEXT, DISTRIBUTION
    }
}