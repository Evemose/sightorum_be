package com.rorm.ai.swarm.phase;

/**
 * Per-hypothesis context extended with the compile-phase output. Passed into
 * the null phase, which needs both the hypothesis bundle and the compiler /
 * pipeline artefacts.
 */
public record PipelineContext(
    HypothesisContext hypothesis,
    CompilePhase.Output compile
) {
}
