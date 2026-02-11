package com.rorm.ai.swarm.dto;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@JsonClassDescription("Complete result of executing a single research step")
public record StepExecutionResultDTO(

    @JsonPropertyDescription("Reference to the executed step")
    @JsonProperty(required = true)
    StepRef stepRef,

    @JsonPropertyDescription("""
        Concise summary of what was accomplished.
        Example: "Identified top 10 customers by revenue in Q1 2024, accounting for 45% of total sales."
        """)
    @JsonProperty(required = true)
    String summary,

    @JsonPropertyDescription("""
        Most important insight from this step.
        Example: "Enterprise segment customers have 3x higher lifetime value than SMB segment."
        """)
    @JsonProperty(required = true)
    String keyInsight,

    @JsonPropertyDescription("Detailed findings, data points, patterns, caveats")
    @JsonProperty(required = true)
    String details,

    @JsonPropertyDescription("Sequence of research actions (reasoning → action → observation)")
    @JsonProperty(required = true)
    List<ResearchActionDTO> researchActions,

    @JsonPropertyDescription("""
        Variables produced: name → computed value.
        Matches ResearchVariable.variableName from plan.
        """)
    @JsonProperty(required = true)
    Map<String, Object> producedVariables,

    @JsonProperty(required = true)
    Instant completedAt

) {
    @JsonClassDescription("Single research action")
    public record ResearchActionDTO(
        @JsonProperty(required = true) String reasoning,
        @JsonProperty(required = true) String action,
        @JsonProperty(required = true) String observation
    ) {}
}