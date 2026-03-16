package com.rorm.durable;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Launches jobs from a {@link JobSpec} and manages their lifecycle.
 * <p>
 * In-memory: resolves the submitter bean, invokes the method directly.
 * Restate: journals the spec, on replay re-resolves and re-invokes.
 */
public interface DurableJobRuntime {

    <T> Awaitable<T> submit(JobSpec spec);

    Object getResult(UUID jobId) throws Exception;

    Object getResult(UUID jobId, long timeout, TimeUnit unit) throws Exception;

    boolean isDone(UUID jobId);
}
