package com.rorm.ai.swarm.phase;

/**
 * Ambient per-anchor state for the swarm pipeline: the shared
 * {@link SwarmRunContext} and the short tag identifying the current
 * anchor.
 *
 * <p>Bound once at the top of each anchor pipeline run (before any phase
 * fires), then read by phase methods via {@link #ctx()} / {@link #anchorTag()}
 * instead of being threaded through every method signature. Uses Java 25
 * {@link ScopedValue} so values are inherited by nested calls without
 * ThreadLocal overhead.
 */
public final class SwarmScope {

    public static final ScopedValue<SwarmRunContext> CTX = ScopedValue.newInstance();
    public static final ScopedValue<String> ANCHOR_TAG = ScopedValue.newInstance();

    private SwarmScope() {
    }

    /**
     * Current swarm run context. Must be called inside a bound scope.
     */
    public static SwarmRunContext ctx() {
        return CTX.get();
    }

    /**
     * Short tag identifying the current anchor. Must be called inside a bound scope.
     */
    public static String anchorTag() {
        return ANCHOR_TAG.get();
    }
}
