package com.rorm.durable;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Durable handle to an async job. Can be persisted and later re-attached.
 * <p>
 * Calling {@link #get()} delegates to the {@link DurableJobRuntime} which:
 * <ul>
 *   <li>Returns the result immediately if the job already completed</li>
 *   <li>Blocks until the job completes if it's still running</li>
 *   <li>Restarts the job and then blocks if it crashed</li>
 * </ul>
 *
 * @param <T> result type of the job's {@code @JobEntry} method
 */
public final class Awaitable<T> {

    private final UUID jobId;
    private final DurableJobRuntime runtime;

    public Awaitable(UUID jobId, DurableJobRuntime runtime) {
        this.jobId = jobId;
        this.runtime = runtime;
    }

    public UUID jobId() {
        return jobId;
    }

    @SuppressWarnings("unchecked")
    public T get() throws Exception {
        return (T) runtime.getResult(jobId);
    }

    @SuppressWarnings("unchecked")
    public T get(long timeout, TimeUnit unit) throws Exception {
        return (T) runtime.getResult(jobId, timeout, unit);
    }

    public boolean isDone() {
        return runtime.isDone(jobId);
    }
}
