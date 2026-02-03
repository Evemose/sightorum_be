package com.rorm.ml.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@JsonNaming(SnakeCaseStrategy.class)
public record PredictionResponse(
    List<Object> predictions,
    List<Map<String, Double>> probabilities,
    UUID modelUuid,
    String modelName,
    String modelType
) {}
