package com.rorm.ml;

import java.util.UUID;

/**
 * Handles for controlling in-flight async ML jobs.
 * Exposed to the client module for UI-driven actions on failed/stuck jobs.
 */
public interface JobControl {

    /**
     * Current status of the durable job await.
     */
    Status status(UUID jobId);

    /**
     * Skip this job — unblocks the awaiter with a synthetic "skipped" event.
     * Use when the user decides a failed job's result is not needed.
     */
    void skip(UUID jobId);

    /**
     * Retry the job — cancels the current await and re-submits the same request.
     * Returns the new job ID.
     */
    UUID retry(UUID jobId);

    /**
     * Cancel the job — aborts the await. The awaiter will return null (timeout-like).
     */
    void cancel(UUID jobId);

    enum Status {PENDING, COMPLETED, NOT_FOUND}
}
