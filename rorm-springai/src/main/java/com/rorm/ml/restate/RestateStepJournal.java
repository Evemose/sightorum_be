package com.rorm.ml.restate;

import com.rorm.durable.StepJournal;
import dev.restate.sdk.Context;
import lombok.RequiredArgsConstructor;

import java.util.function.Supplier;

@RequiredArgsConstructor
public class RestateStepJournal implements StepJournal {

    private final Context ctx;

    @Override
    public <T> T run(String stepName, Class<T> resultType, Supplier<T> action) {
        return ctx.run(stepName, resultType, action::get);
    }
}
