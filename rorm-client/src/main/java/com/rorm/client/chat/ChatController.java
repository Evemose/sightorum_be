package com.rorm.client.chat;

import com.rorm.client.chat.dto.ChatMessageRequest;
import com.rorm.client.stream.SseEmitterRegistry;
import com.rorm.client.utils.WithSchema;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/datasets/{schema}/chat")
@RequiredArgsConstructor
@Validated
public class ChatController {

    private final ChatService chatService;
    private final SseEmitterRegistry sseRegistry;

    @WithSchema("schema")
    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(
        @PathVariable String schema,
        @Valid @RequestBody ChatMessageRequest request
    ) {
        var sessionId = request.sessionId() != null ? request.sessionId() : UUID.randomUUID().toString();
        var topic = ChatService.chatTopic(sessionId);
        var emitter = sseRegistry.register(topic, null);
        chatService.streamResponse(schema, sessionId, request.message());
        log.info("Chat started: schema={}, sessionId={}", schema, sessionId);
        return emitter;
    }

    @GetMapping(value = "/{sessionId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter reconnect(
        @PathVariable String schema,
        @PathVariable String sessionId,
        @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId
    ) {
        log.info("Chat SSE reconnect: schema={}, sessionId={}, lastEventId={}", schema, sessionId, lastEventId);
        return sseRegistry.register(ChatService.chatTopic(sessionId), lastEventId);
    }
}
