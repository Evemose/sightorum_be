package com.rorm;

import java.util.List;
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

    /**
     * Journal-level fanout: launches N actions as parallel journal entries
     * and awaits all completions before returning.
     * <p>
     * This is a <b>core journaling primitive</b>, not a convenience wrapper.
     * In a durable runtime (Restate), the command ordering within a single
     * invocation is what makes replay deterministic. Fanout must:
     * <ol>
     *   <li>Append all {@code runAsync} commands to the journal in a deterministic
     *       (index-based) order — this is guaranteed because the {@code Context}
     *       is single-threaded.</li>
     *   <li>Use the runtime's native {@code DurableFuture.all()} combinator so
     *       the runtime knows these entries form a parallel group and can schedule
     *       their closures concurrently during execution (and replay completions
     *       in the recorded order on recovery).</li>
     *   <li>Support <b>nested fanout</b>: an action's closure may itself call
     *       {@code fanout}, creating nested journal entries interleaved with the
     *       parent group. The journal captures the full execution-order sequence,
     *       which is replayed identically.</li>
     * </ol>
     * Each implementation must use its runtime's native parallel execution and
     * completion-tracking mechanism — the in-memory journal executes sequentially
     * (deterministic by construction), while Restate delegates to
     * {@code ctx.runAsync()} + {@code dev.restate.sdk.DurableFuture.all()}.
     *
     * @param stepPrefix base name for journal entries ({@code stepPrefix + ":0"}, etc.)
     * @param resultType class token for serialization
     * @param actions    suppliers to execute in parallel; order determines journal index
     * @return results in the same order as {@code actions}
     */
    <T> List<T> fanout(String stepPrefix, Class<T> resultType, List<Supplier<T>> actions);

    <T> DurableFuture<T> awakeable(Class<T> type);

    UUID randomUUID();

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

        /**
         * In-memory fanout: submits all actions to {@link java.util.concurrent.CompletableFuture#supplyAsync}
         * first (mirroring Restate's deferred closure execution), then joins via
         * {@code allOf}. All submissions happen before any closure starts, matching
         * Restate's command-then-execute ordering.
         */
        @Override
        public <T> List<T> fanout(String stepPrefix, Class<T> resultType, List<Supplier<T>> actions) {
            if (actions.isEmpty()) {
                return List.of();
            }
            var cfs = actions.stream()
                .map(a -> java.util.concurrent.CompletableFuture.supplyAsync(a::get))
                .toList();
            java.util.concurrent.CompletableFuture.allOf(cfs.toArray(java.util.concurrent.CompletableFuture[]::new)).join();
            return cfs.stream().map(java.util.concurrent.CompletableFuture::join).toList();
        }

        public AwakableHandle<?> getAwakableHandle(String typeName) {
            return awakableHandles.get(typeName);
        }

    }
}
