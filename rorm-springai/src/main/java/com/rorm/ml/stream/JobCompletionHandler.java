package com.rorm.ml.stream;

import com.rorm.DurableFuture;

import java.util.UUID;

/**
 * Handles ML job lifecycle events from the Redis stream listener.
 * <p>
 * In-memory implementation completes {@link com.rorm.CompletableDurableFuture}s directly.
 * Restate implementation resolves awakeables via the Restate server.
 * <p>
 * {@link #register(UUID, DurableFuture)} must be called outside journalled steps
 * so it re-executes on Restate replay, restoring the in-memory mapping.
 */
public interface JobCompletionHandler {

    void register(UUID jobId, DurableFuture<JobEvent> future);

    void onJobSuccess(JobEvent event);

    void onJobFailure(JobEvent event);

    void onJobProgress(JobEvent event);
}
