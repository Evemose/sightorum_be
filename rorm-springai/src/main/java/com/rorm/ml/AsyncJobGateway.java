package com.rorm.ml;

import com.rorm.ml.dto.AsyncJobRequest;

import java.util.UUID;

/**
 * Submits an async ML job to Python and registers it for durable await.
 * Returns the job ID. Tracking launched jobs is the caller's responsibility.
 */
public interface AsyncJobGateway {

    UUID submit(AsyncJobRequest request, String schema);
}
