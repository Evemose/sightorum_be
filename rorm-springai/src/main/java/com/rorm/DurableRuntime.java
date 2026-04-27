package com.rorm;

import java.util.List;

public interface DurableRuntime {

    /**
     * Blocking dispatch: submits a sub-invocation and waits for its result.
     * Suitable when the caller wants a synchronous return value and is willing
     * to block its thread (or its handler thread, when called from inside a
     * Restate invocation).
     */
    Object submit(String sessionId, JobSpec spec);

    /**
     * Parallel sub-invocation fanout: dispatches all branches concurrently
     * and awaits all completions. Each branch is a real Restate sub-invocation
     * (or an in-memory job) with its own journal — branches may freely use
     * awakeables, nested fanout, etc. Results are returned as raw {@code Object}s
     * (Jackson-deserialized by the runtime layer); callers convert to their
     * desired type.
     */
    default List<Object> fanout(List<JobInvocation> invocations) {
        if (invocations.isEmpty()) {
            return List.of();
        }
        var futures = invocations.stream()
            .map(inv -> submitAsync(inv.sessionId(), inv.spec()))
            .toList();
        DurableFuture.all(futures.toArray(DurableFuture[]::new)).await();
        return futures.stream().map(DurableFuture::await).toList();
    }

    /**
     * Non-blocking dispatch: submits a sub-invocation and returns a
     * {@link DurableFuture} that resolves when the child completes. Multiple
     * such futures can be combined with {@link DurableFuture#all} for real
     * parallel execution under Restate (each child invocation owns its own
     * journal, so awakeable-driven work inside the child is legal).
     */
    DurableFuture<Object> submitAsync(String sessionId, JobSpec spec);

    <T> AwakableHandle<T> handle(String awakableId, Class<T> resultType);
}
