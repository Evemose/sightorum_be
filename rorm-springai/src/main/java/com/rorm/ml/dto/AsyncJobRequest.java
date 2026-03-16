package com.rorm.ml.dto;

import com.rorm.ml.peristence.MLJobType;
import org.jspecify.annotations.Nullable;

/**
 * Contract for any async job request that participates in the fire-listen-wakeup lifecycle.
 * Implementations are serialized to JSONB for uniform persistence.
 *
 * <p>Sealed to ensure exhaustive handling and to derive {@link #jobType()} from the concrete type,
 * eliminating the possibility of enum/request mismatch.
 */
public sealed interface AsyncJobRequest
    permits TrainingJobRequest, TuningJobRequest, StabilitySelectionJobRequest, ShapJobRequest {

    String reason();

    @Nullable
    default String furtherInstructions() {
        return null;
    }

    MLJobType jobType();
}
