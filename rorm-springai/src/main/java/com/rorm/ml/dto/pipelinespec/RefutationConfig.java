package com.rorm.ml.dto.pipelinespec;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(SnakeCaseStrategy.class)
@JsonClassDescription("One refutation check against the primary estimate.")
public record RefutationConfig(

    @JsonPropertyDescription("""
        Refutation type: PLACEBO (permute the treatment column),
        RANDOM_CAUSE (add a random common cause), SUBSET (re-estimate on
        random subsamples), or TEMPORAL_PLACEBO (lag the treatment by a
        fake time offset). TEMPORAL_PLACEBO is only meaningful when the
        data spans multiple time periods.""")
    @JsonProperty(required = true)
    RefutationType type
) {

    @JsonClassDescription("""
        Refutation strategy. PLACEBO permutes the treatment column and
        re-estimates, expecting a null effect. RANDOM_CAUSE adds a fresh
        random column as a fake common cause and checks that the ATE
        barely moves. SUBSET re-estimates on random subsamples and checks
        ATE stability. TEMPORAL_PLACEBO lags the treatment by a fake time
        offset to test spurious temporal correlation; only meaningful when
        the data spans multiple time periods.""")
    public enum RefutationType {
        PLACEBO,
        RANDOM_CAUSE,
        SUBSET,
        TEMPORAL_PLACEBO
    }
}
