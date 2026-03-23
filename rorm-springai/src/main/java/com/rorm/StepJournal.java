package com.rorm;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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
    InMemory DEFAULT = new InMemory();

    static StepJournal current() {
        return CURRENT.isBound() ? CURRENT.get() : DEFAULT;
    }

    @SuppressWarnings("unchecked")
    default <T> DurableFuture<T> runAsync(String stepName, Supplier<T> action) {
        return (DurableFuture<T>) runAsync(stepName, Object.class, (Supplier<Object>) action);
    }

    @SuppressWarnings("unchecked")
    default <T> T run(String stepName, Supplier<T> action) {
        return (T) run(stepName, Object.class, (Supplier<Object>) action);
    }

    <T> T run(String stepName, Class<T> resultType, Supplier<T> action);

    <T> DurableFuture<T> runAsync(String stepName, Class<T> resultType, Supplier<T> action);

    class InMemory implements StepJournal {

        private final ConcurrentMap<String, AwakableHandle<?>> awakableHandles = new ConcurrentHashMap<>();

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
            var future = CompletableDurableFuture.<T>pending();
            awakableHandles.putIfAbsent(type.getName(), new AwakableHandle<T>() {
                @Override
                public void resolve(T result) {
                    future.complete(result);
                }

                @Override
                public void reject(String reason) {
                    future.completeExceptionally(new RuntimeException(reason));
                }
            });
            return future;
        }

        @Override
        public UUID randomUUID() {
            return UUID.randomUUID();
        }

        public AwakableHandle<?> getAwakableHandle(String typeName) {
            return awakableHandles.get(typeName);
        }

    }

    <T> DurableFuture<T> awakeable(Class<T> type);

    UUID randomUUID();
}
