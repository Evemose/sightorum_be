package com.rorm.ai.swarm.phase;

/**
 * Per-hypothesis context carried into the compile phase. Holds its parent
 * {@link AnchorContext}, the gen-phase output that produced the hypothesis
 * list, and the hypothesis identifier this branch is working on. The rebuttal
 * id, spec block, and domain raw are all derivable from these fields.
 */
public record HypothesisContext(
    AnchorContext anchor,
    GenPhase.Output gen,
    String hypothesisId
) {
}
