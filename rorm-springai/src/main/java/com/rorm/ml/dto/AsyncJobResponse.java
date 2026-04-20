package com.rorm.ml.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.UUID;

@JsonNaming(SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record AsyncJobResponse(
    String status,
    UUID analysisId,
    String message
) {
    @JsonIgnore
    public boolean isNotAccepted() {
        return !"accepted".equals(status);
    }
}
