package com.rorm.ai.swarm.dto;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

@JsonClassDescription("Generator agent output containing causal hypotheses")
public record HypothesisGenerationDTO(

    @JsonPropertyDescription("List of causal hypotheses generated for the anchor perspective")
    @JsonProperty(required = true)
    List<Hypothesis> hypotheses
) {

    @JsonClassDescription("A single causal hypothesis with identifier and full specification")
    public record Hypothesis(

        @JsonPropertyDescription("Hypothesis identifier (e.g. H1, H2)")
        @JsonProperty(required = true)
        String id,

        @JsonPropertyDescription("Full specification including treatment, outcome, expected direction, DAG edges, and evidence")
        @JsonProperty(required = true)
        String specification
    ) {}
}
