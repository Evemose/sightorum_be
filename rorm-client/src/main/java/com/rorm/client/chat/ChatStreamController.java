package com.rorm.client.chat;

import com.rorm.client.stream.SseEmitterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

/**
 * SSE streaming endpoints for chat sessions.
 * Uses HTTP/2 for efficient multiplexed streaming.
 */
@Slf4j
@RestController
@RequestMapping("/chat/sessions/{id}/stream")
@RequiredArgsConstructor
public class ChatStreamController {

    private final SseEmitterRegistry registry;

    /**
     * SSE stream for chat tree updates - new nodes, status changes, etc.
     * Clients should subscribe here for real-time tree updates.
     *
     * @param id          Session ID
     * @param lastEventId Last event ID for reconnection (from Last-Event-ID header)
     * @return SSE emitter for tree updates
     */
    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamTree(
        @PathVariable UUID id,
        @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId
    ) {
        log.info("New SSE connection for chat tree: session={}, lastEventId={}", id, lastEventId);
        var topic = "chat:tree:" + id;
        return registry.register(topic, lastEventId);
    }

    /**
     * SSE stream for current message generation - token-by-token streaming.
     * Use this when you want to show real-time typing effect for AI responses.
     *
     * @param id          Session ID
     * @param lastEventId Last event ID for reconnection (from Last-Event-ID header)
     * @return SSE emitter for current message tokens
     */
    @GetMapping(value = "/current", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamCurrent(
        @PathVariable UUID id,
        @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId
    ) {
        log.info("New SSE connection for current message: session={}, lastEventId={}", id, lastEventId);
        var topic = "chat:current:" + id;
        return registry.register(topic, lastEventId);
    }
}
