package com.rorm.ml.restate;

import com.rorm.DurableFuture;
import com.rorm.StepJournal;
import dev.restate.sdk.ObjectContext;
import dev.restate.sdk.common.StateKey;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public class RestateStepJournal implements StepJournal {

    private final ObjectContext ctx;
    private String keyPrefix;
    private int callOrdinal;

    public RestateStepJournal(ObjectContext ctx) {
        this.ctx = ctx;
        this.keyPrefix = "";
        this.callOrdinal = 0;
    }

    @Override
    public <T> T run(String stepName, Class<T> resultType, Supplier<T> action) {
        var qualifiedName = keyPrefix + callOrdinal++ + ":" + stepName;
        var stateKey = StateKey.of(qualifiedName, resultType);
        var cached = ctx.get(stateKey);
        if (cached.isPresent()) {
            return cached.get();
        }
        var result = ctx.run(qualifiedName, resultType, action::get);
        ctx.set(stateKey, result);
        return result;
    }

    @Override
    public <T> DurableFuture<T> runAsync(String stepName, Class<T> resultType, Supplier<T> action) {
        var qualifiedName = keyPrefix + callOrdinal++ + ":" + stepName;
        var cached = ctx.get(StateKey.of(qualifiedName, resultType));
        if (cached.isPresent()) {
            return new RestateDurableFuture.Resolved<>(resultType.cast(cached.get()), UUID.randomUUID().toString());
        }
        var future = ctx.runAsync(qualifiedName, resultType, action::get);
        return new RestateDurableFuture.Delegated<>(
            future.map(result -> {
                ctx.set(StateKey.of(qualifiedName, resultType), result);
                return result;
            }),
            UUID.randomUUID().toString()
        );
    }

    @Override
    public <T> DurableFuture<T> awakeable(Class<T> type) {
        var awakeable = ctx.awakeable(type);
        return new RestateDurableFuture.Delegated<>(awakeable, awakeable.id());
    }

    @Override
    public UUID randomUUID() {
        return ctx.random().nextUUID();
    }

    /**
     * Restate fanout: executes each action sequentially on the handler thread.
     * Each action's body contains its own {@code ctx.run()} calls (LLM calls,
     * nested fanout) which are individually journaled and recoverable.
     * <p>
     * The fanout itself does NOT wrap actions in {@code ctx.run()} — that would
     * nest journal operations inside a side-effect, which is illegal in Restate
     * ({@code ctx.run()} closures must not call {@code ctx.run()} again).
     * <p>
     * On replay, Restate replays the individual journal entries inside each
     * action (returning cached results for completed steps), then continues
     * live execution from the point of failure.
     */
    @Override
    public <T> List<T> fanout(String stepPrefix, Class<T> resultType, List<Supplier<T>> actions) {
        if (actions.isEmpty()) {
            return List.of();
        }
        var savedPrefix = this.keyPrefix;
        var results = new ArrayList<T>(actions.size());
        for (int i = 0; i < actions.size(); i++) {
            this.keyPrefix = savedPrefix + stepPrefix + "-" + i + "/";
            results.add(actions.get(i).get());
        }
        this.keyPrefix = savedPrefix;
        return results;
    }
}
