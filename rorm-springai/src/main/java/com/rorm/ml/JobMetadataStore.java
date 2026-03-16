package com.rorm.ml;

import com.rorm.ml.peristence.MLJobInfo;

import java.util.Optional;
import java.util.UUID;

/**
 * Read-only access to persisted job metadata.
 * <p>
 * Used by the executor during phase-2 prompt building to recover
 * the reason and instructions for each completed training.
 */
public interface JobMetadataStore {

    Optional<MLJobInfo> findByJobId(UUID jobId);
}
