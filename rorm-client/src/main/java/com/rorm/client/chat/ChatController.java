package com.rorm.client.chat;

import com.rorm.client.chat.dto.ChatMessageRequest;
import com.rorm.client.chat.session.SessionService;
import com.rorm.client.stream.SseEmitterRegistry;
import com.rorm.client.utils.WithSchema;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@RestController
@RequestMapping("/datasets/{schema}/chat")
@RequiredArgsConstructor
@Validated
public class ChatController {

    private final ChatService chatService;
    private final SessionService sessionService;
    private final SseEmitterRegistry sseRegistry;

    @WithSchema("schema")
    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(
        @PathVariable String schema,
        @Valid @RequestBody ChatMessageRequest request
    ) {
        var sessionId = request.sessionId() != null ? request.sessionId() : SessionService.newId();
        var session = sessionService.findOrCreate(sessionId, schema);
        var topic = ChatService.chatTopic(session.getPrimaryChatId());
        var emitter = sseRegistry.register(topic, null);
        chatService.streamResponse(schema, session.getId(), session.getPrimaryChatId(), request.message());
        log.info("Chat message: schema={}, sessionId={}", schema, session.getId());
        return emitter;
    }

    @GetMapping(value = "/{sessionId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter reconnect(
        @PathVariable String schema,
        @PathVariable String sessionId,
        @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId
    ) {
        var session = sessionService.get(sessionId);
        log.info("Chat SSE reconnect: schema={}, sessionId={}, lastEventId={}", schema, sessionId, lastEventId);
        return sseRegistry.register(ChatService.chatTopic(session.getPrimaryChatId()), lastEventId);
    }
}
