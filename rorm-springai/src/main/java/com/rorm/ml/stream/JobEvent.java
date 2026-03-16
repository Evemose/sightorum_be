package com.rorm.ml.stream;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Event received from Python via Redis stream or via Restate awakeable resolution.
 * Uses explicit {@code @JsonProperty} (not {@code @JsonNaming}) so that both Spring's
 * configured ObjectMapper and Restate's default ObjectMapper can handle it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JobEvent(
    @JsonProperty("job_id") UUID jobId,
    @JsonProperty("event_type") JobEventType eventType,
    @JsonProperty("timestamp") Instant timestamp,
    @JsonProperty("progress") double progress,
    @JsonProperty("message") String message,
    @JsonProperty("metrics") Map<String, Object> metrics,
    @JsonProperty("error") String error,
    @JsonProperty("error_code") String errorCode,
    @JsonProperty("metadata") Map<String, Object> metadata
) {
    public JobEvent {
        if (metrics == null) {
            metrics = Map.of();
        }
        if (metadata == null) {
            metadata = Map.of();
        }
    }

    public boolean isSuccess() {
        return eventType == JobEventType.JOB_SUCCESS;
    }

    public boolean isFailed() {
        return eventType == JobEventType.JOB_FAILED ||
               eventType == JobEventType.VALIDATION_FAILED;
    }

    public boolean isStarted() {
        return eventType == JobEventType.JOB_STARTED;
    }

    public boolean isProgress() {
        return JobEventType.JOB_PROGRESS.equals(eventType);
    }
}
