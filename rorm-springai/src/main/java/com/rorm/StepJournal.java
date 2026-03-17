package com.rorm;

import java.util.function.Supplier;

/**
 * Journals non-deterministic steps for replay.
 * <p>
 * On first execution: runs the supplier and caches the result.
 * On replay: returns the cached result without re-executing.
 * <p>
 * Everything deterministic (local computation, list building, prompt construction)
 * does NOT need journaling — it recomputes identically from journaled results.
 */
@SuppressWarnings("preview")
public interface StepJournal {

    ScopedValue<StepJournal> CURRENT = ScopedValue.newInstance();
    StepJournal NOOP = new StepJournal() {
        @Override
        public <T> T run(String stepName, Class<T> resultType, Supplier<T> action) {
            return action.get();
        }
    };

    static StepJournal current() {
        return CURRENT.isBound() ? CURRENT.get() : NOOP;
    }

    @SuppressWarnings("unchecked")
    default <T> T run(String stepName, Supplier<T> action) {
        return (T) run(stepName, Object.class, (Supplier<Object>) action);
    }

    <T> T run(String stepName, Class<T> resultType, Supplier<T> action);
}
