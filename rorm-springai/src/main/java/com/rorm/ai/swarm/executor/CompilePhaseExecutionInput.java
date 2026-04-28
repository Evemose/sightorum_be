package com.rorm.ai.swarm.executor;

import com.rorm.ai.swarm.phase.HypothesisContext;

/**
 * Input for {@link CompilePhaseExecutor}. Bundles the per-hypothesis context
 * with the swarm run id so the executor — running as its own Restate
 * sub-invocation — can rebind the {@code runId} into PhaseScope.
 */
public record CompilePhaseExecutionInput(HypothesisContext hypoCtx, String runId) {
}
