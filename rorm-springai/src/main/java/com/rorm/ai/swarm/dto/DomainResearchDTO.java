package com.rorm.ai.swarm.dto;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

@JsonClassDescription("Domain research findings including causal priors and external knowledge")
public record DomainResearchDTO(

    @JsonPropertyDescription("Summary of domain knowledge relevant to the research question")
    @JsonProperty(required = true)
    String summary,

    @JsonPropertyDescription("Known causal relationships or priors from domain literature")
    @JsonProperty(required = true)
    List<String> causalPriors,

    @JsonPropertyDescription("Domain-specific constraints that affect causal analysis")
    @JsonProperty(required = true)
    List<String> constraints,

    @JsonPropertyDescription("Potential confounding variables identified from domain knowledge")
    @JsonProperty(required = true)
    List<String> confounders
) {}
