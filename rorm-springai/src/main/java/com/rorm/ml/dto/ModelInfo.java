package com.rorm.ml.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@JsonNaming(SnakeCaseStrategy.class)
public record ModelInfo(
    UUID modelUuid,
    String modelName,
    String modelType,
    String modelCategory,
    List<String> featureColumns,
    String targetColumn,
    int trainingRows,
    int trainingColumns,
    Map<String, Object> modelParams,
    Map<String, Object> trainingMetrics,
    Instant createdAt,
    String storagePath,
    String predictionEndpoint
) {}
