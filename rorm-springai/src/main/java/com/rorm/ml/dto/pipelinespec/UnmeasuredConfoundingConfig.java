package com.rorm.ml.dto.pipelinespec;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(SnakeCaseStrategy.class)
@JsonClassDescription("""
    Unmeasured-confounding sensitivity analysis for a single estimation
    variant. E_VALUE computes how strong an unmeasured confounder would
    need to be to explain away the estimate; ROSENBAUM_BOUNDS computes
    the departure from random assignment needed to change the inference.""")
public record UnmeasuredConfoundingConfig(

    @JsonPropertyDescription("Id of the estimation variant this analysis targets.")
    @JsonProperty(required = true)
    String variantId,

    @JsonPropertyDescription("Analysis method: E_VALUE | ROSENBAUM_BOUNDS.")
    @JsonProperty(required = true)
    UnmeasuredMethod method,

    @JsonPropertyDescription("""
        Null hypothesis under test, written as a short expression over
        the ATE (e.g. 'ATE = 0' or 'ATE < X').""")
    @JsonProperty(required = true)
    String nullHypothesis,

    @JsonPropertyDescription("Narrative note on what strength of confounding would nullify the estimate.")
    @JsonProperty(required = true)
    String notes
) {

    @JsonClassDescription("""
        Unmeasured-confounding sensitivity method. E_VALUE reports the
        minimum strength of association that an unmeasured confounder
        would need with both treatment and outcome (above the measured
        confounders) to explain away the observed estimate. ROSENBAUM_BOUNDS
        reports the critical Gamma: the departure from random treatment
        assignment that would be required to change the inference.""")
    public enum UnmeasuredMethod {
        E_VALUE,
        ROSENBAUM_BOUNDS
    }
}
