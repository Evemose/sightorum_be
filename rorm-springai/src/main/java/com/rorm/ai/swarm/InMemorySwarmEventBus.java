package com.rorm.ai.swarm;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-process event bus backed by Reactor {@link Sinks}. Suitable for
 * {@link com.rorm.ml.runtime.InMemoryDurableRuntime} where all invocations
 * run in the same JVM.
 */
public class InMemorySwarmEventBus implements SwarmEventBus {

    private final ConcurrentMap<String, Sinks.Many<SwarmStreamEvent>> sinks = new ConcurrentHashMap<>();

    @Override
    public void publish(String runId, SwarmStreamEvent event) {
        sinkFor(runId).tryEmitNext(event);
    }

    private Sinks.Many<SwarmStreamEvent> sinkFor(String runId) {
        return sinks.computeIfAbsent(runId,
            _ -> Sinks.many().multicast().onBackpressureBuffer());
    }

    @Override
    public Flux<SwarmStreamEvent> subscribe(String runId) {
        return sinkFor(runId).asFlux();
    }

    @Override
    public void complete(String runId) {
        var sink = sinks.remove(runId);
        if (sink != null) {
            sink.tryEmitComplete();
        }
    }
}
