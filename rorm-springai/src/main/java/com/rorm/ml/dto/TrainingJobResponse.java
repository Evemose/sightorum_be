package com.rorm.ml.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.UUID;

@JsonNaming(SnakeCaseStrategy.class)
public record TrainingJobResponse(
    String status,
    UUID trainingId,
    String message
) {
    public boolean isNotAccepted() {
        return !"accepted".equals(status);
    }
}
