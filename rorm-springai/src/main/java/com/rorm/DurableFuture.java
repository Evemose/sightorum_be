package com.rorm;

/**
 * A durable, awaitable future that survives process crashes.
 * <p>
 * Created via {@link StepJournal#runAsync} (runtime provides the result)
 * or {@link StepJournal#awakeable} (external system provides the result).
 * <p>
 * Use static {@link #all} for fanout composition.
 * All futures in a single composition must originate from the same runtime.
 */
public interface DurableFuture<T> {

    <U> DurableFuture<U> map(java.util.function.Function<T, U> mapper);

    static DurableFuture<Void> all(DurableFuture<?>... futures) {
        if (futures.length == 0) {
            throw new IllegalArgumentException("No futures provided");
        }
        return futures[0].combineAll(futures);
    }

    DurableFuture<Void> combineAll(DurableFuture<?>... futures);

    T await();

    String id();
}
