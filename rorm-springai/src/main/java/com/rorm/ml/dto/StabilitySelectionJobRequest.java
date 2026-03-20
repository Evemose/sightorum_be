package com.rorm.ml.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.rorm.ml.peristence.MLJobType;
import lombok.Builder;
import org.jspecify.annotations.Nullable;

import java.util.List;

@Builder
@JsonNaming(SnakeCaseStrategy.class)
public record StabilitySelectionJobRequest(
    @JsonIgnore String reason,
    DatasourceConfig datasource,
    String targetColumn,
    @Nullable List<String> featureColumns,
    @Nullable List<String> controlFeatures,
    @Nullable String problemType,
    int bootstrapRuns,
    double sampleFraction,
    double correlationThreshold,
    @Nullable Integer selectionTopK,
    int randomState
) implements AsyncJobRequest {

    @Override
    @JsonIgnore
    public MLJobType jobType() {
        return MLJobType.STABILITY_SELECTION;
    }
}
