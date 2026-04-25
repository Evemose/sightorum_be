package com.rorm.ai.swarm.dto;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

@JsonClassDescription("""
    Final judge output synthesizing advocate/prosecutor standoffs across all
    hypotheses for an anchor into a single coherent answer to the user's
    original query. The response itself is free-form prose; the headline is
    an extracted one-sentence bottom line for downstream display.""")
public record JudgeVerdictDTO(

    @JsonPropertyDescription("""
        One-sentence headline capturing the judge's bottom line answer to
        the user's query.""")
    @JsonProperty(required = true)
    String headline,

    @JsonPropertyDescription("""
        Full free-form prose response to the user, verbatim as produced by
        the judge. This is the user-facing answer.""")
    @JsonProperty(required = true)
    String response
) {}
