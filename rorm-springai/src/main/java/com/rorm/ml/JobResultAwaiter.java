package com.rorm.ml;

import com.rorm.ml.stream.JobEvent;
import org.jspecify.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Awaits async ML job completion.
 * <p>
 * Implementations encapsulate the transport-specific blocking mechanism.
 * With Redis: backs onto a ConcurrentHashMap of CompletableFutures.
 * With Temporal: delegates to WorkflowStub.getResult().
 */
public interface JobResultAwaiter {

    /**
     * Block until the job completes or the timeout elapses.
     *
     * @return the terminal event (success or failure), or {@code null} on timeout / unknown error
     * @throws InterruptedException if the waiting thread is interrupted
     */
    @Nullable
    JobEvent await(UUID jobId, long timeout, TimeUnit unit) throws InterruptedException;

    /**
     * Clean up internal state for a completed job.
     * Must be called after {@link #await} returns, regardless of outcome.
     */
    void remove(UUID jobId);
}
