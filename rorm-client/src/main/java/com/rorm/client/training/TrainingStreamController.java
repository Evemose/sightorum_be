package com.rorm.client.training;

import com.rorm.client.stream.SseEmitterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

/**
 * SSE streaming endpoints for ML training progress.
 * Used for on-demand progress tracking (e.g., hover over training indicator).
 */
@Slf4j
@RestController
@RequestMapping("/training/{trainingId}/stream")
@RequiredArgsConstructor
public class TrainingStreamController {

    private final SseEmitterRegistry registry;

    /**
     * SSE stream for training progress updates.
     * Publishes events: training_started, training_progress, training_success, training_failed
     *
     * @param trainingId  Training ID
     * @param lastEventId Last event ID for reconnection
     * @return SSE emitter for training progress updates
     */
    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamProgress(
        @PathVariable UUID trainingId,
        @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId
    ) {
        log.info("New SSE connection for training progress: trainingId={}, lastEventId={}", trainingId, lastEventId);
        var topic = "training:" + trainingId;
        return registry.register(topic, lastEventId);
    }
}
