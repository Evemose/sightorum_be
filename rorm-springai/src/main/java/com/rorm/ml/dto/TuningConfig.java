package com.rorm.ml.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;

@Builder
@JsonNaming(SnakeCaseStrategy.class)
public record TuningConfig(
    Integer nTrials,
    Integer maxTuningTime,
    String metric
) {
    public TuningConfig {
        if (nTrials == null) {
            nTrials = 50;
        }
        if (maxTuningTime == null) {
            maxTuningTime = 300;
        }
        if (metric == null) {
            metric = "auto";
        }
    }

    public static TuningConfig defaults() {
        return new TuningConfig(null, null, null);
    }
}
