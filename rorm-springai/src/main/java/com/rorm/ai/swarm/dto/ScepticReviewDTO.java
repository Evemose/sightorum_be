package com.rorm.ai.swarm.dto;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

@JsonClassDescription("Mechanical sceptic's verification of generator hypotheses")
public record ScepticReviewDTO(

    @JsonPropertyDescription("Individual verification findings for each hypothesis claim")
    @JsonProperty(required = true)
    List<Finding> findings,

    @JsonPropertyDescription("Overall coverage summary of the verification")
    @JsonProperty(required = true)
    String coverageSummary
) {

    @JsonClassDescription("Sceptic's verification finding for a specific claim")
    public record Finding(

        @JsonPropertyDescription("The claim being verified")
        @JsonProperty(required = true)
        String claim,

        @JsonPropertyDescription("Verdict: SUPPORTED, CONDITIONAL, REFUTED")
        @JsonProperty(required = true)
        String verdict,

        @JsonPropertyDescription("Detail or delta explanation for the verdict")
        @JsonProperty(required = true)
        String detail
    ) {}
}
