package com.rorm.client.research;

import com.rorm.client.research.dto.ResearchProgress;
import com.rorm.client.stream.SseEmitterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Publishes research events to SSE subscribers.
 * Applies backpressure on token events by batching per nodeId using {@code bufferTimeout}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResearchEventPublisher {

    private static final int TOKEN_BATCH_SIZE = 50;
    private static final Duration TOKEN_BATCH_TIMEOUT = Duration.ofMillis(100);

    private final SseEmitterRegistry registry;
    private final AtomicLong eventCounter = new AtomicLong(0);

    public void publishNodeStart(UUID researchId, String nodeId, String nodeType) {
        publish(researchId, "node_start", new ResearchProgress.NodeStarted(researchId, nodeId, nodeType));
    }

    private void publish(UUID researchId, String eventType, ResearchProgress event) {
        var eventId = String.valueOf(eventCounter.incrementAndGet());
        registry.publish(topic(researchId), eventType, eventId, event);
    }

    private static String topic(UUID researchId) {
        return "research:" + researchId;
    }

    /**
     * Subscribes to a token stream and publishes batched token events to SSE.
     * Backpressure: buffers up to {@value TOKEN_BATCH_SIZE} tokens or flushes every
     * 100 ms, whichever comes first.
     */
    public void subscribeTokenStream(UUID researchId, String nodeId, Flux<String> tokenStream) {
        tokenStream
            .bufferTimeout(TOKEN_BATCH_SIZE, TOKEN_BATCH_TIMEOUT)
            .subscribe(
                batch -> {
                    var text = String.join("", batch);
                    publish(researchId, "tokens", new ResearchProgress.Tokens(researchId, nodeId, text));
                },
                error -> log.warn("Token stream error for node {}: {}", nodeId, error.getMessage())
            );
    }

    public void publishNodeEnd(UUID researchId, String nodeId, String nodeType, Object findings) {
        publish(researchId, "node_end", new ResearchProgress.NodeFinished(researchId, nodeId, nodeType, findings));
    }

    public void publishComplete(UUID researchId) {
        publish(researchId, "research_complete", new ResearchProgress.ResearchComplete(researchId));
        registry.complete(topic(researchId));
    }

    public void publishError(UUID researchId, String message) {
        publish(researchId, "research_failed", new ResearchProgress.ResearchFailed(researchId, message));
        registry.error(topic(researchId), message);
    }
}
