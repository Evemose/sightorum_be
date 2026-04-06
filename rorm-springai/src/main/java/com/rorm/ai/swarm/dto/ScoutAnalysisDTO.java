package com.rorm.ai.swarm.dto;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

@JsonClassDescription("Scout agent analysis of the data landscape for causal research")
public record ScoutAnalysisDTO(

    @JsonPropertyDescription("Summary of the scout's reconnaissance findings")
    @JsonProperty(required = true)
    String summary,

    @JsonPropertyDescription("Entity clusters discovered (tables, concepts, relationship groups)")
    @JsonProperty(required = true)
    List<String> entityClusters,

    @JsonPropertyDescription("Notable patterns, anomalies, or statistical relationships observed")
    @JsonProperty(required = true)
    List<String> patterns,

    @JsonPropertyDescription("Recommended research directions and anchor points for causal analysis")
    @JsonProperty(required = true)
    List<String> recommendations
) {}
