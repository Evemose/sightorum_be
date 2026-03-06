package com.rorm.ml.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.UUID;

@JsonNaming(SnakeCaseStrategy.class)
public record AsyncJobResponse(
    String status,
    UUID analysisId,
    String message
) {
    public boolean isNotAccepted() {
        return !"accepted".equals(status);
    }
}
