package com.rorm;

import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * In-memory {@link DurableFuture} backed by {@link CompletableFuture}.
 * Used by {@link StepJournal#NOOP} and the in-memory runtime.
 */
public class CompletableDurableFuture<T> implements DurableFuture<T> {

    private final CompletableFuture<T> delegate;
    private final String id;

    private CompletableDurableFuture(CompletableFuture<T> delegate, String id) {
        this.delegate = delegate;
        this.id = id;
    }

    public static <T> CompletableDurableFuture<T> completed(T value) {
        return new CompletableDurableFuture<>(CompletableFuture.completedFuture(value), UUID.randomUUID().toString());
    }

    public static <T> CompletableDurableFuture<T> pending() {
        return new CompletableDurableFuture<>(new CompletableFuture<>(), UUID.randomUUID().toString());
    }

    @Override
    public T await() {
        return delegate.join();
    }

    @Override
    public String id() {
        return id;
    }

    public void complete(T value) {
        delegate.complete(value);
    }

    public void completeExceptionally(Throwable cause) {
        delegate.completeExceptionally(cause);
    }

    @Override
    public <U> DurableFuture<U> map(Function<T, U> mapper) {
        return new CompletableDurableFuture<>(delegate.thenApply(mapper), UUID.randomUUID().toString());
    }

    @Override
    public DurableFuture<Void> combineAll(DurableFuture<?>... futures) {
        var cfs = Arrays.stream(futures)
            .map(f -> ((CompletableDurableFuture<?>) f).delegate)
            .toArray(CompletableFuture[]::new);
        return new CompletableDurableFuture<>(CompletableFuture.allOf(cfs), UUID.randomUUID().toString());
    }
}
