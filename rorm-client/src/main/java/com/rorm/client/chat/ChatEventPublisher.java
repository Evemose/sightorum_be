package com.rorm.client.chat;

import com.rorm.ai.chat.node.ChatNode;
import com.rorm.client.chat.dto.ChatNodeDTO;
import com.rorm.client.stream.SseEmitterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Publishes chat events to SSE subscribers.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatEventPublisher {

    private final SseEmitterRegistry registry;
    private final ChatNodeMapper nodeMapper;

    private final AtomicLong eventCounter = new AtomicLong(0);

    /**
     * Publishes a new chat node to all subscribers of the session.
     */
    public void publishNode(UUID sessionId, ChatNode node) {
        var topic = buildTreeTopic(sessionId);
        var dto = nodeMapper.toDTO(node);
        var eventId = generateEventId();
        registry.publish(topic, "node", eventId, dto);
        log.debug("Published node {} to session {}", node.getId(), sessionId);
    }

    private String buildTreeTopic(UUID sessionId) {
        return "chat:tree:" + sessionId;
    }

    private String generateEventId() {
        return String.valueOf(eventCounter.incrementAndGet());
    }

    /**
     * Publishes a new chat node DTO to all subscribers of the session.
     */
    public void publishNode(UUID sessionId, ChatNodeDTO nodeDTO) {
        var topic = buildTreeTopic(sessionId);
        var eventId = generateEventId();
        registry.publish(topic, "node", eventId, nodeDTO);
        log.debug("Published node DTO {} to session {}", nodeDTO.id(), sessionId);
    }

    /**
     * Publishes a streaming token to the current message stream.
     */
    public void publishToken(UUID sessionId, String token) {
        var topic = buildCurrentTopic(sessionId);
        var eventId = generateEventId();
        registry.publish(topic, "token", eventId, token);
    }

    private String buildCurrentTopic(UUID sessionId) {
        return "chat:current:" + sessionId;
    }

    /**
     * Publishes a streaming completion event.
     */
    public void publishStreamComplete(UUID sessionId) {
        var topic = buildCurrentTopic(sessionId);
        registry.complete(topic);
    }

    /**
     * Publishes a streaming error event.
     */
    public void publishStreamError(UUID sessionId, String errorMessage) {
        var topic = buildCurrentTopic(sessionId);
        registry.error(topic, errorMessage);
    }

    /**
     * Publishes a session status update.
     */
    public void publishStatusUpdate(UUID sessionId, String status) {
        var topic = buildTreeTopic(sessionId);
        var eventId = generateEventId();
        registry.publish(topic, "status", eventId, new StatusUpdate(sessionId, status));
    }

    /**
     * Checks if there are any active subscribers for a session.
     */
    public boolean hasSubscribers(UUID sessionId) {
        return registry.hasSubscribers(buildTreeTopic(sessionId))
               || registry.hasSubscribers(buildCurrentTopic(sessionId));
    }

    private record StatusUpdate(UUID sessionId, String status) {}
}
