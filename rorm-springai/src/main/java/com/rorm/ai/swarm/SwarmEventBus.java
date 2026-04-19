package com.rorm.ai.swarm;

import reactor.core.publisher.Flux;

/**
 * Publish/subscribe bus for {@link SwarmStreamEvent}s during a swarm run.
 * Each run is identified by a {@code runId}; all executors within that run
 * publish to the same stream, and any number of subscribers can consume
 * the events as a {@link Flux}.
 */
public interface SwarmEventBus {

    void publish(String runId, SwarmStreamEvent event);

    Flux<SwarmStreamEvent> subscribe(String runId);

    void complete(String runId);
}
