package com.rorm.ai.swarm.dto;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

@JsonClassDescription("Reference to a specific step in the research plan")
public record StepRef(
    @JsonPropertyDescription("Branch containing the step")
    @JsonProperty(required = true)
    String branchId,

    @JsonPropertyDescription("Step within that branch")
    @JsonProperty(required = true)
    String stepId
) {
}