package com.rorm.ml.stream;

import com.rorm.ml.JobResultAwaiter;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.*;

@Slf4j
@org.springframework.stereotype.Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
    name = "rorm.ml.durable-execution", havingValue = "false", matchIfMissing = true
)
public class JobFutureRegistry implements JobResultAwaiter {

    private final ConcurrentMap<UUID, CompletableFuture<JobEvent>> futures = new ConcurrentHashMap<>();

    public CompletableFuture<JobEvent> register(UUID jobId) {
        var future = new CompletableFuture<JobEvent>();
        futures.put(jobId, future);
        return future;
    }

    public void complete(UUID jobId, JobEvent event) {
        var future = futures.get(jobId);
        if (future != null) {
            future.complete(event);
        }
    }

    public void completeExceptionally(UUID jobId, Throwable cause) {
        var future = futures.get(jobId);
        if (future != null) {
            future.completeExceptionally(cause);
        }
    }

    @Override
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

    @Override
    public void remove(UUID jobId) {
        futures.remove(jobId);
    }
}
