package com.rorm.ai.swarm;

/**
 * Ambient per-swarm-run state carried through the phase call tree via
 * Java 25 {@link ScopedValue} — avoids threading the {@code runId} through
 * every phase and helper signature. Bound once at the top of
 * {@link DurableSwarm#run(SwarmInput, String)} and inherited by nested calls.
 */
public final class PhaseScope {

    public static final ScopedValue<String> RUN_ID = ScopedValue.newInstance();

    private PhaseScope() {
    }

    public static String runId() {
        return RUN_ID.get();
    }
}
