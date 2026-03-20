package com.rorm.ml.restate;

import com.rorm.DurableFuture;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@link DurableFuture} backed by Restate's durable future / awakeable.
 * Delegates {@link #await()} to the Restate SDK which handles journal replay.
 */
record RestateDurableFuture<T>(dev.restate.sdk.DurableFuture<T> delegate, String id) implements DurableFuture<T> {

    @Override
    public T await() {
        return delegate.await();
    }

    @Override
    public DurableFuture<Void> combineAll(DurableFuture<?>... futures) {
        List<dev.restate.sdk.DurableFuture<?>> restate = Arrays.stream(futures)
            .map(f -> (dev.restate.sdk.DurableFuture<?>) ((RestateDurableFuture<?>) f).delegate)
            .collect(Collectors.toList());
        var combined = dev.restate.sdk.DurableFuture.all(restate);
        return new RestateDurableFuture<>(combined, UUID.randomUUID().toString());
    }

    @Override
    public DurableFuture<Integer> combineAny(DurableFuture<?>... futures) {
        List<dev.restate.sdk.DurableFuture<?>> restate = Arrays.stream(futures)
            .map(f -> (dev.restate.sdk.DurableFuture<?>) ((RestateDurableFuture<?>) f).delegate)
            .collect(Collectors.toList());
        var combined = dev.restate.sdk.DurableFuture.any(restate);
        return new RestateDurableFuture<>(combined, UUID.randomUUID().toString());
    }
}
