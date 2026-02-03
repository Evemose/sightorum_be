package com.rorm.client.stream;

import com.rorm.client.config.RormClientProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Registry for managing Server-Sent Events (SSE) connections.
 * Supports multiple subscribers per topic and handles heartbeats to keep connections alive.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SseEmitterRegistry {

    private final RormClientProperties properties;

    // Topic -> List of emitters for that topic
    private final Map<String, CopyOnWriteArrayList<SseEmitterWrapper>> emitters = new ConcurrentHashMap<>();

    /**
     * Creates a new SSE emitter and registers it for the given topic.
     *
     * @param topic   The topic to subscribe to (e.g., "chat:session-id", "import:job-id")
     * @param eventId Optional last event ID for reconnection
     * @return A configured SseEmitter
     */
    public SseEmitter register(String topic, String eventId) {
        var emitter = new SseEmitter(0L); // No timeout - we handle via heartbeat
        var wrapper = new SseEmitterWrapper(emitter, eventId);

        emitters.computeIfAbsent(topic, _ -> new CopyOnWriteArrayList<>()).add(wrapper);

        // Set up cleanup callbacks
        Runnable cleanup = () -> {
            var list = emitters.get(topic);
            if (list != null) {
                list.remove(wrapper);
                if (list.isEmpty()) {
                    emitters.remove(topic);
                }
            }
        };

        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> {
            log.debug("SSE error for topic {}: {}", topic, e.getMessage());
            cleanup.run();
        });

        log.debug("Registered SSE emitter for topic: {}, eventId: {}", topic, eventId);
        return emitter;
    }

    /**
     * Publishes an event to all subscribers of a topic.
     *
     * @param topic     The topic to publish to
     * @param eventName The SSE event name
     * @param eventId   The event ID for reconnection tracking
     * @param data      The event data
     */
    public void publish(String topic, String eventName, String eventId, Object data) {
        var list = emitters.get(topic);
        if (list == null || list.isEmpty()) {
            return;
        }

        var event = SseEmitter.event()
            .name(eventName)
            .id(eventId)
            .data(data)
            .reconnectTime(properties.sse().reconnectDelayMs());

        var toRemove = new CopyOnWriteArrayList<SseEmitterWrapper>();

        for (var wrapper : list) {
            try {
                wrapper.emitter.send(event);
                wrapper.lastEventId = eventId;
            } catch (IOException e) {
                log.debug("Failed to send SSE event to topic {}: {}", topic, e.getMessage());
                toRemove.add(wrapper);
            }
        }

        list.removeAll(toRemove);
        if (list.isEmpty()) {
            emitters.remove(topic);
        }
    }

    /**
     * Publishes multiple events (useful for catch-up on reconnection).
     */
    public void publishAll(String topic, Consumer<EventBuilder> eventBuilder) {
        var list = emitters.get(topic);
        if (list == null || list.isEmpty()) {
            return;
        }

        for (var wrapper : list) {
            eventBuilder.accept(new EventBuilder(wrapper, properties.sse().reconnectDelayMs()));
        }
    }

    /**
     * Sends a complete signal to all subscribers and removes them.
     */
    public void complete(String topic) {
        var list = emitters.remove(topic);
        if (list == null) {
            return;
        }

        for (var wrapper : list) {
            try {
                wrapper.emitter.send(SseEmitter.event().name("complete").data(""));
                wrapper.emitter.complete();
            } catch (IOException e) {
                log.debug("Error completing SSE emitter for topic {}: {}", topic, e.getMessage());
            }
        }
    }

    /**
     * Sends an error signal to all subscribers.
     */
    public void error(String topic, String message) {
        var list = emitters.get(topic);
        if (list == null) {
            return;
        }

        for (var wrapper : list) {
            try {
                wrapper.emitter.send(SseEmitter.event().name("error").data(message));
                wrapper.emitter.completeWithError(new RuntimeException(message));
            } catch (IOException e) {
                log.debug("Error sending error event for topic {}: {}", topic, e.getMessage());
            }
        }

        emitters.remove(topic);
    }

    /**
     * Checks if there are any active subscribers for a topic.
     */
    public boolean hasSubscribers(String topic) {
        var list = emitters.get(topic);
        return list != null && !list.isEmpty();
    }

    /**
     * Sends heartbeat to all active connections to keep them alive.
     * Uses a comment event which is ignored by browsers but keeps the connection open.
     */
    @Scheduled(fixedRateString = "${rorm.client.sse.heartbeat-interval-ms:30000}")
    public void sendHeartbeats() {
        for (var entry : emitters.entrySet()) {
            var topic = entry.getKey();
            var list = entry.getValue();
            var toRemove = new CopyOnWriteArrayList<SseEmitterWrapper>();

            for (var wrapper : list) {
                try {
                    wrapper.emitter.send(SseEmitter.event().comment("heartbeat"));
                } catch (IOException e) {
                    log.debug("Heartbeat failed for topic {}, removing emitter", topic);
                    toRemove.add(wrapper);
                }
            }

            list.removeAll(toRemove);
            if (list.isEmpty()) {
                emitters.remove(topic);
            }
        }
    }

    /**
     * Helper class to build events for a specific emitter.
     */
    public static class EventBuilder {
        private final SseEmitterWrapper wrapper;
        private final long reconnectTime;

        EventBuilder(SseEmitterWrapper wrapper, long reconnectTime) {
            this.wrapper = wrapper;
            this.reconnectTime = reconnectTime;
        }

        public String getLastEventId() {
            return wrapper.lastEventId;
        }

        public void send(String eventName, String eventId, Object data) {
            try {
                wrapper.emitter.send(
                    SseEmitter.event()
                        .name(eventName)
                        .id(eventId)
                        .data(data)
                        .reconnectTime(reconnectTime)
                );
                wrapper.lastEventId = eventId;
            } catch (IOException e) {
                throw new RuntimeException("Failed to send SSE event", e);
            }
        }
    }

    private static class SseEmitterWrapper {
        final SseEmitter emitter;
        String lastEventId;

        SseEmitterWrapper(SseEmitter emitter, String lastEventId) {
            this.emitter = emitter;
            this.lastEventId = lastEventId;
        }
    }
}
