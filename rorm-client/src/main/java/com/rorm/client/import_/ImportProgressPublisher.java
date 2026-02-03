package com.rorm.client.import_;

import com.rorm.client.import_.dto.ImportProgressEvent;
import com.rorm.client.stream.SseEmitterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@RequiredArgsConstructor
public class ImportProgressPublisher {

    private final SseEmitterRegistry registry;
    private final ImportJobQueue jobQueue;
    private final AtomicLong eventCounter = new AtomicLong(0);

    /**
     * Publishes a progress event to both Redis (for distribution) and local SSE subscribers.
     */
    public void publish(ImportProgressEvent event) {
        // Publish to Redis for distributed subscribers
        jobQueue.publishProgress(event);

        // Also publish directly to local SSE subscribers
        publishToSse(event);
    }

    /**
     * Publishes directly to local SSE subscribers (used by Redis listener).
     */
    public void publishToSse(ImportProgressEvent event) {
        var topic = "import:" + event.jobId();
        var eventId = String.valueOf(eventCounter.incrementAndGet());
        var eventType = getEventType(event);
        registry.publish(topic, eventType, eventId, event);
        log.debug("Published import progress: job={}, type={}", event.jobId(), eventType);
    }

    private String getEventType(ImportProgressEvent event) {
        return switch (event) {
            case ImportProgressEvent.Chunk c -> "chunk";
            case ImportProgressEvent.StepComplete s -> "step_complete";
            case ImportProgressEvent.JobComplete j -> "job_complete";
            case ImportProgressEvent.Error e -> "error";
        };
    }

    /**
     * Checks if there are any active subscribers for a job.
     */
    public boolean hasSubscribers(UUID jobId) {
        return registry.hasSubscribers("import:" + jobId);
    }

    /**
     * Signals that the import job stream is complete.
     */
    public void complete(UUID jobId) {
        registry.complete("import:" + jobId);
    }

    /**
     * Signals an error on the import job stream.
     */
    public void error(UUID jobId, String message) {
        registry.error("import:" + jobId, message);
    }
}
