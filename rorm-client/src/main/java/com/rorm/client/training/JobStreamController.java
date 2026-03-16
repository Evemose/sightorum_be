package com.rorm.client.training;

import com.rorm.client.stream.SseEmitterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

/**
 * SSE streaming endpoints for ML job progress.
 * Used for on-demand progress tracking (e.g., hover over job indicator).
 */
@Slf4j
@RestController
@RequestMapping("/job/{jobId}/stream")
@RequiredArgsConstructor
public class JobStreamController {

    private final SseEmitterRegistry registry;

    /**
     * SSE stream for job progress updates.
     * Publishes events: job_started, job_progress, job_success, job_failed
     *
     * @param jobId       Job ID
     * @param lastEventId Last event ID for reconnection
     * @return SSE emitter for job progress updates
     */
    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamProgress(
        @PathVariable UUID jobId,
        @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId
    ) {
        log.info("New SSE connection for job progress: jobId={}, lastEventId={}", jobId, lastEventId);
        var topic = "job:" + jobId;
        return registry.register(topic, lastEventId);
    }
}
