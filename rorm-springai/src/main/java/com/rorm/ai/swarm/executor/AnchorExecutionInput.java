package com.rorm.ai.swarm.executor;

import com.rorm.ai.swarm.SwarmInput;
import com.rorm.ai.swarm.phase.ReconPhase;

/**
 * Input for {@link AnchorExecutor}: per-anchor context plus the upstream recon
 * output and run id. Carried as a single record so it can be Jackson-serialized
 * across a Restate sub-invocation boundary.
 */
public record AnchorExecutionInput(
    SwarmInput swarm,
    String anchor,
    String anchorTag,
    ReconPhase.Output recon,
    String runId
) {
}
