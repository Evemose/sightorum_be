package com.rorm.ml.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.rorm.ml.peristence.MLJobType;
import org.jspecify.annotations.Nullable;

import java.util.List;

@JsonNaming(SnakeCaseStrategy.class)
public record ShapJobRequest(
    @JsonIgnore String reason,
    String runId,
    @Nullable List<String> features,
    int nBins,
    int nBreakpoints
) implements AsyncJobRequest {

    @Override
    public String furtherInstructions() {
        return "Interpret the SHAP dependence curves: identify thresholds, non-linearities, and actionable insights.";
    }

    @Override
    @JsonIgnore
    public MLJobType jobType() {
        return MLJobType.SHAP;
    }
}
