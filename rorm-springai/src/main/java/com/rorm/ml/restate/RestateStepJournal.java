package com.rorm.ml.restate;

import com.rorm.DurableFuture;
import com.rorm.StepJournal;
import dev.restate.sdk.ObjectContext;
import dev.restate.sdk.common.StateKey;
import lombok.RequiredArgsConstructor;

import java.util.UUID;
import java.util.function.Supplier;

@RequiredArgsConstructor
public class RestateStepJournal implements StepJournal {

    private final ObjectContext ctx;

    @Override
    public <T> T run(String stepName, Class<T> resultType, Supplier<T> action) {
        var stateKey = StateKey.of(stepName, resultType);
        var cached = ctx.get(stateKey);
        if (cached.isPresent()) {
            return cached.get();
        }
        var result = ctx.run(stepName, resultType, action::get);
        ctx.set(stateKey, result);
        return result;
    }

    @Override
    public <T> DurableFuture<T> runAsync(String stepName, Class<T> resultType, Supplier<T> action) {
        var cached = ctx.get(StateKey.of(stepName, resultType));
        if (cached.isPresent()) {
            return new RestateDurableFuture.Resolved<>(resultType.cast(cached.get()), UUID.randomUUID().toString());
        }
        var future = ctx.runAsync(stepName, resultType, action::get);
        return new RestateDurableFuture.Delegated<>(
            future.map(result -> {
                ctx.set(StateKey.of(stepName, resultType), result);
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
}
