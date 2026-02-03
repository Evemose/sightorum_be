package com.rorm.ml.stream;

import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@JsonNaming(SnakeCaseStrategy.class)
public record TrainingEvent(
    UUID trainingId,
    TrainingEventType eventType,
    Instant timestamp,
    Double progress,
    String message,
    Map<String, Object> metrics,
    String error,
    String errorCode,
    Map<String, Object> metadata
) {
    public boolean isSuccess() {
        return eventType == TrainingEventType.TRAINING_SUCCESS;
    }

    public boolean isFailed() {
        return eventType == TrainingEventType.TRAINING_FAILED ||
               eventType == TrainingEventType.VALIDATION_FAILED;
    }

    public boolean isStarted() {
        return eventType == TrainingEventType.TRAINING_STARTED;
    }

    public boolean isProgress() {
        return TrainingEventType.TRAINING_PROGRESS.equals(eventType);
    }
}
