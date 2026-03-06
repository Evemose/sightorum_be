package com.rorm.ml.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
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
    @Nullable String problemType,
    int bootstrapRuns,
    double sampleFraction,
    double correlationThreshold,
    int polynomialDegree,
    @Nullable Integer selectionTopK,
    int randomState
) {}
