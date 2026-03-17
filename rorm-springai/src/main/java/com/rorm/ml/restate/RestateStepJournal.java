package com.rorm.ml.restate;

import com.rorm.durable.StepJournal;
import dev.restate.sdk.ObjectContext;
import dev.restate.sdk.common.StateKey;
import lombok.RequiredArgsConstructor;

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
}
