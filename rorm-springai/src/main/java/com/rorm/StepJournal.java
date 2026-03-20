package com.rorm;

import java.util.UUID;
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

        @Override
        public <T> DurableFuture<T> runAsync(String stepName, Class<T> resultType, Supplier<T> action) {
            return CompletableDurableFuture.completed(action.get());
        }

        @Override
        public <T> DurableFuture<T> awakeable(Class<T> type) {
            return CompletableDurableFuture.pending();
        }

        @Override
        public UUID randomUUID() {
            return UUID.randomUUID();
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

    <T> DurableFuture<T> runAsync(String stepName, Class<T> resultType, Supplier<T> action);

    <T> DurableFuture<T> awakeable(Class<T> type);

    UUID randomUUID();
}
