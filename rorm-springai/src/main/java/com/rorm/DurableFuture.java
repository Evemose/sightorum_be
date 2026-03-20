package com.rorm;

/**
 * A durable, awaitable future that survives process crashes.
 * <p>
 * Created via {@link StepJournal#runAsync} (runtime provides the result)
 * or {@link StepJournal#awakeable} (external system provides the result).
 * <p>
 * Use static {@link #all} / {@link #any} for fanout composition.
 * All futures in a single composition must originate from the same runtime.
 */
public interface DurableFuture<T> {

    static DurableFuture<Void> all(DurableFuture<?>... futures) {
        if (futures.length == 0) {
            throw new IllegalArgumentException("No futures provided");
        }
        return futures[0].combineAll(futures);
    }

    DurableFuture<Void> combineAll(DurableFuture<?>... futures);

    static DurableFuture<Integer> any(DurableFuture<?>... futures) {
        if (futures.length == 0) {
            throw new IllegalArgumentException("No futures provided");
        }
        return futures[0].combineAny(futures);
    }

    DurableFuture<Integer> combineAny(DurableFuture<?>... futures);

    T await();

    String id();
}
