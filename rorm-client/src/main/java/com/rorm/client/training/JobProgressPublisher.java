package com.rorm.client.training;

import com.rorm.client.stream.SseEmitterRegistry;
import com.rorm.ml.stream.JobEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Publishes job progress events to SSE subscribers.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobProgressPublisher {

    private final SseEmitterRegistry registry;
    private final AtomicLong eventCounter = new AtomicLong(0);

    /**
     * Publishes a job event to subscribers.
     */
    public void publish(JobEvent event) {
        var topic = buildTopic(event.jobId());
        var eventId = String.valueOf(eventCounter.incrementAndGet());
        var dto = toDTO(event);
        var eventTypeName = getEventTypeName(dto);

        registry.publish(topic, eventTypeName, eventId, dto);
        log.debug("Published job event: jobId={}, type={}", event.jobId(), eventTypeName);

        // Complete the stream if job finished
        if (dto instanceof JobProgressDTO.Success || dto instanceof JobProgressDTO.Failed) {
            registry.complete(topic);
        }
    }

    private String buildTopic(UUID jobId) {
        return "job:" + jobId;
    }

    private JobProgressDTO toDTO(JobEvent event) {
        return switch (event.eventType()) {
            case JOB_STARTED -> new JobProgressDTO.Started(
                event.jobId(),
                event.message(),
                event.timestamp()
            );
            case JOB_PROGRESS -> new JobProgressDTO.Progress(
                event.jobId(),
                event.progress(),
                event.message(),
                event.timestamp()
            );
            case JOB_SUCCESS -> new JobProgressDTO.Success(
                event.jobId(),
                extractModelPath(event),
                event.metrics(),
                event.timestamp()
            );
            case JOB_FAILED, VALIDATION_FAILED -> new JobProgressDTO.Failed(
                event.jobId(),
                event.error(),
                event.errorCode(),
                event.timestamp()
            );
        };
    }

    private String getEventTypeName(JobProgressDTO dto) {
        return switch (dto) {
            case JobProgressDTO.Started s -> "job_started";
            case JobProgressDTO.Progress p -> "job_progress";
            case JobProgressDTO.Success s -> "job_success";
            case JobProgressDTO.Failed f -> "job_failed";
        };
    }

    private String extractModelPath(JobEvent event) {
        if (event.metadata() != null && event.metadata().containsKey("modelPath")) {
            return String.valueOf(event.metadata().get("modelPath"));
        }
        if (event.metrics() != null && event.metrics().containsKey("modelPath")) {
            return String.valueOf(event.metrics().get("modelPath"));
        }
        return null;
    }

    /**
     * Checks if there are any active subscribers for a job.
     */
    public boolean hasSubscribers(UUID jobId) {
        return registry.hasSubscribers(buildTopic(jobId));
    }

    public sealed interface JobProgressDTO permits
        JobProgressDTO.Started,
        JobProgressDTO.Progress,
        JobProgressDTO.Success,
        JobProgressDTO.Failed {

        UUID jobId();

        Instant timestamp();

        record Started(
            UUID jobId,
            String message,
            Instant timestamp
        ) implements JobProgressDTO {}

        record Progress(
            UUID jobId,
            double progress,
            String message,
            Instant timestamp
        ) implements JobProgressDTO {}

        record Success(
            UUID jobId,
            String modelPath,
            Map<String, Object> metrics,
            Instant timestamp
        ) implements JobProgressDTO {}

        record Failed(
            UUID jobId,
            String error,
            String errorCode,
            Instant timestamp
        ) implements JobProgressDTO {}
    }
}
