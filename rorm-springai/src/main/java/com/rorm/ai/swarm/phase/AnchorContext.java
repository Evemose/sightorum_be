package com.rorm.ai.swarm.phase;

import com.rorm.ai.swarm.SwarmInput;

/**
 * Per-anchor context carried across the gen/compile/null phases. Bundles the
 * original {@link SwarmInput}, the anchor entity text, its short tag, and the
 * upstream recon result so downstream phases can take a single argument.
 */
public record AnchorContext(
    SwarmInput swarm,
    String anchor,
    String anchorTag,
    ReconPhase.Output recon
) {
}
