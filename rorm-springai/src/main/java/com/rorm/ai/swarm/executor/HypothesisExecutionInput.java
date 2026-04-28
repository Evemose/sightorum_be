package com.rorm.ai.swarm.executor;

import com.rorm.ai.swarm.phase.AnchorContext;
import com.rorm.ai.swarm.phase.GenPhase;

/**
 * Input for {@link HypothesisExecutor}: everything needed to run the
 * compile + (optional) null + standoff sequence for a single hypothesis as
 * its own Restate sub-invocation.
 */
public record HypothesisExecutionInput(
    AnchorContext anchor,
    GenPhase.Output gen,
    String hypothesisTitle,
    String runId
) {
}
