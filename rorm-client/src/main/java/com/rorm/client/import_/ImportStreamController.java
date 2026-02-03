package com.rorm.client.import_;

import com.rorm.client.stream.SseEmitterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

/**
 * SSE streaming endpoints for import job progress.
 * Uses HTTP/2 for efficient multiplexed streaming.
 */
@Slf4j
@RestController
@RequestMapping("/import/jobs/{id}/stream")
@RequiredArgsConstructor
public class ImportStreamController {

    private final SseEmitterRegistry registry;

    /**
     * SSE stream for import job progress updates.
     * Publishes events: chunk, step_complete, job_complete, error
     *
     * @param id          Job ID
     * @param lastEventId Last event ID for reconnection
     * @return SSE emitter for progress updates
     */
    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamProgress(
        @PathVariable UUID id,
        @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId
    ) {
        log.info("New SSE connection for import progress: job={}, lastEventId={}", id, lastEventId);
        var topic = "import:" + id;
        return registry.register(topic, lastEventId);
    }
}
