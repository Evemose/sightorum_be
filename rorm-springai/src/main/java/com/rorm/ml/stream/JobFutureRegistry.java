package com.rorm.ml.stream;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.*;

@Slf4j
@Component
public class JobFutureRegistry {

    private final ConcurrentMap<UUID, CompletableFuture<JobEvent>> futures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, JobEvent> missed = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Throwable> failed = new ConcurrentHashMap<>();

    public CompletableFuture<JobEvent> register(UUID jobId) {
        if (missed.containsKey(jobId)) {
            var event = missed.remove(jobId);
            log.info("Completing future for job {} from missed events", jobId);
            var future = new CompletableFuture<JobEvent>();
            future.complete(event);
            return future;
        } else if (failed.containsKey(jobId)) {
            var cause = failed.remove(jobId);
            log.info("Completing future for job {} from failed events", jobId);
            var future = new CompletableFuture<JobEvent>();
            future.completeExceptionally(cause);
            return future;
        }
        return futures.computeIfAbsent(jobId, _ -> {
            log.info("Creating new future for job {}", jobId);
            return new CompletableFuture<>();
        });
    }

    public void complete(UUID jobId, JobEvent event) {
        var future = futures.get(jobId);
        if (future != null) {
            future.complete(event);
        } else {
            missed.put(jobId, event);
        }
    }

    public void completeExceptionally(UUID jobId, Throwable cause) {
        var future = futures.get(jobId);
        if (future != null) {
            future.completeExceptionally(cause);
        } else {
            failed.put(jobId, cause);
        }
    }

    @Nullable
    public JobEvent await(UUID jobId, long timeout, TimeUnit unit) throws InterruptedException {
        var future = futures.get(jobId);
        if (future == null) {
            return null;
        }
        try {
            return future.get(timeout, unit);
        } catch (TimeoutException e) {
            log.warn("Job {} timed out after {} {}", jobId, timeout, unit);
            return null;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof JobFailedException jfe) {
                return jfe.event();
            }
            log.warn("Error awaiting job {}: {}", jobId, e.getMessage());
            return null;
        }
    }

    CompletableFuture<JobEvent> get(UUID jobId) {
        return futures.get(jobId);
    }

    public void remove(UUID jobId) {
        futures.remove(jobId);
    }
}
