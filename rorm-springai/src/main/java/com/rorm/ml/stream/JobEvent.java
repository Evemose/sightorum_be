package com.rorm.ml.stream;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

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
    @JsonProperty("metadata") Map<String, Object> metadata,
    @JsonProperty("worker_task_arn") @Nullable String workerTaskArn
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
