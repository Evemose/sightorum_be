package com.rorm.ai.swarm.phase;

/**
 * Builds the {@code rawResponse} text downstream agents read across
 * supervisor loops. The first round's raw is the agent's own output
 * verbatim — for a straight pass-through hypothesis this is the
 * value carried in {@code StepOutput.rawResponse} and serialized JSON
 * is byte-identical to the pre-loop implementation. Each subsequent
 * round wraps the prior accumulated text with a fixed marker so
 * downstream agents (standoff, supervisor, null) see the full
 * iteration trail without any schema change.
 */
public final class IterationHistoryRenderer {

    private IterationHistoryRenderer() {
    }

    public static String append(String priorRaw, String newRaw, String label, int iteration) {
        return priorRaw + "\n\n--- " + label + " iteration " + iteration + " ---\n\n" + newRaw;
    }
}
