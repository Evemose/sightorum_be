package com.rorm.ai.swarm.communication;

import com.rorm.ml.dto.PipelineSpecRequest;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Per-step slot for the compiled {@link PipelineSpecRequest} a compiler
 * agent finalizes through {@code validatePipelineSpec}. The holder is
 * installed into the swarm tool context for the duration of the
 * compiler step; the validation tool deposits the spec on every
 * successful pass, and the compiler-specific secondary agent reads it
 * after streaming completes instead of summarizing raw text.
 */
public final class PipelineSpecHolder {

    private final AtomicReference<PipelineSpecRequest> ref = new AtomicReference<>();

    public void set(PipelineSpecRequest spec) {
        ref.set(spec);
    }

    public Optional<PipelineSpecRequest> get() {
        return Optional.ofNullable(ref.get());
    }
}
