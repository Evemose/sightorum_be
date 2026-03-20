package com.rorm.ml.stream;

import com.rorm.CompletableDurableFuture;
import com.rorm.DurableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory {@link JobCompletionHandler} that completes {@link CompletableDurableFuture}s
 * and falls back to {@link JobFutureRegistry} for non-durable callers.
 * <p>
 * Uses the same first-to-arrive-stores / second-to-arrive-resolves pattern as
 * {@link com.rorm.ml.restate.RestateJobCompletionHandler} to handle out-of-order
 * event/registration arrival.
 */
@Slf4j
@RequiredArgsConstructor
public class JobEventsSupport implements JobCompletionHandler {

    private final JobFutureRegistry registry;

    // Value is either CompletableDurableFuture<JobEvent> (from register) or JobEvent (early-arriving)
    private final ConcurrentMap<UUID, Object> pending = new ConcurrentHashMap<>();

    @Override
    @SuppressWarnings("unchecked")
    public void register(UUID jobId, DurableFuture<JobEvent> future) {
        var df = (CompletableDurableFuture<JobEvent>) future;
        var prev = pending.putIfAbsent(jobId, df);
        if (prev instanceof JobEvent event) {
            pending.remove(jobId);
            log.info("Completing future for job {} (event arrived before registration)", jobId);
            completeFuture(df, event);
        }
    }

    private void completeFuture(CompletableDurableFuture<JobEvent> df, JobEvent event) {
        if (event.isSuccess()) {
            df.complete(event);
        } else {
            df.completeExceptionally(new JobFailedException(event));
        }
    }

    @Override
    public void onJobSuccess(JobEvent event) {
        resolveEvent(event);
    }

    @SuppressWarnings("unchecked")
    private void resolveEvent(JobEvent event) {
        var prev = pending.putIfAbsent(event.jobId(), event);
        if (prev instanceof CompletableDurableFuture<?> df) {
            pending.remove(event.jobId());
            log.info("Completing future for job {}", event.jobId());
            completeFuture((CompletableDurableFuture<JobEvent>) df, event);
        } else {
            // Not a durable-registered job, or event buffered for later registration.
            // Try the fallback registry for non-durable callers.
            if (event.isSuccess()) {
                registry.complete(event.jobId(), event);
            } else {
                registry.completeExceptionally(event.jobId(), new JobFailedException(event));
            }
        }
    }

    @Override
    public void onJobFailure(JobEvent event) {
        resolveEvent(event);
    }

    @Override
    public void onJobProgress(JobEvent event) {
        log.debug("Recording job progress for job {}: {}%",
            event.jobId(),
            String.format("%.1f", event.progress() * 100));
    }
}
