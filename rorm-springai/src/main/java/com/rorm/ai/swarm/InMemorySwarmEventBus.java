package com.rorm.ai.swarm;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-process event bus backed by Reactor {@link Sinks}. Suitable for
 * {@link com.rorm.ml.runtime.InMemoryDurableRuntime} where all invocations
 * run in the same JVM.
 * <p>
 * Per-run dedup: every {@link SwarmStreamEvent} carries a stable
 * {@link SwarmStreamEvent#dedupKey()}. The bus tracks the set of keys
 * already emitted for each {@code runId} and silently drops repeats so
 * that mid-stream interrupt + retry, parent-invocation replay outside a
 * journal context, or any other source of duplicate publishes never
 * reaches a subscriber. Dedup state is cleared on
 * {@link #complete(String)}.
 */
public class InMemorySwarmEventBus implements SwarmEventBus {

    private final ConcurrentMap<String, Sinks.Many<SwarmStreamEvent>> sinks = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Set<String>> seenKeys = new ConcurrentHashMap<>();

    @Override
    public void publish(String runId, SwarmStreamEvent event) {
        var seen = seenKeys.computeIfAbsent(runId, _ -> ConcurrentHashMap.newKeySet());
        if (!seen.add(event.dedupKey())) {
            return;
        }
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
        seenKeys.remove(runId);
        var sink = sinks.remove(runId);
        if (sink != null) {
            sink.tryEmitComplete();
        }
    }
}
