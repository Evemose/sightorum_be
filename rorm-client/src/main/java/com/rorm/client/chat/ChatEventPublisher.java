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
    private final ClientChatNodeMapper nodeMapper;

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
     * Publishes an update to an existing chat node.
     */
    public void publishNodeUpdate(UUID sessionId, ChatNode node) {
        var topic = buildTreeTopic(sessionId);
        var dto = nodeMapper.toDTO(node);
        var eventId = generateEventId();
        registry.publish(topic, "node-update", eventId, dto);
        log.debug("Published node update {} to session {}", node.getId(), sessionId);
    }

    private String buildTreeTopic(UUID sessionId) {
        return "chat:tree:" + sessionId;
    }

    private String generateEventId() {
        return String.valueOf(eventCounter.incrementAndGet());
    }

    /**
     * Publishes an update to an existing chat node DTO.
     */
    public void publishNodeUpdate(UUID sessionId, ChatNodeDTO nodeDTO) {
        var topic = buildTreeTopic(sessionId);
        var eventId = generateEventId();
        registry.publish(topic, "node-update", eventId, nodeDTO);
        log.debug("Published node update DTO {} to session {}", nodeDTO.id(), sessionId);
    }

    /**
     * Publishes a node deletion event.
     */
    public void publishNodeDeleted(UUID sessionId, UUID nodeId) {
        var topic = buildTreeTopic(sessionId);
        var eventId = generateEventId();
        registry.publish(topic, "node-deleted", eventId, new NodeDeleted(nodeId));
        log.debug("Published node deleted {} to session {}", nodeId, sessionId);
    }

    /**
     * Publishes a streaming completion event.
     */
    public void publishStreamComplete(UUID sessionId) {
        var topic = buildTreeTopic(sessionId);
        var eventId = generateEventId();
        registry.publish(topic, "complete", eventId, null);
    }

    /**
     * Publishes a streaming error event.
     */
    public void publishStreamError(UUID sessionId, String errorMessage) {
        var topic = buildTreeTopic(sessionId);
        var eventId = generateEventId();
        registry.publish(topic, "error", eventId, new StreamError(errorMessage));
    }

    /**
     * Publishes a session status update.
     */
    public void publishStatusUpdate(UUID sessionId, String status) {
        var topic = buildTreeTopic(sessionId);
        var eventId = generateEventId();
        registry.publish(topic, "status", eventId, new StatusUpdate(sessionId, status));
    }

    private record StatusUpdate(UUID sessionId, String status) {}

    private record NodeDeleted(UUID nodeId) {}

    private record StreamError(String message) {}
}
