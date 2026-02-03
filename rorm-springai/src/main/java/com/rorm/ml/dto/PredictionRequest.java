package com.rorm.ml.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;
import java.util.Map;

@JsonNaming(SnakeCaseStrategy.class)
public record PredictionRequest(
    Object inputData
) {
    public static PredictionRequest single(Map<String, Object> features) {
        return new PredictionRequest(features);
    }

    public static PredictionRequest batch(List<Map<String, Object>> featuresList) {
        return new PredictionRequest(featuresList);
    }
}
