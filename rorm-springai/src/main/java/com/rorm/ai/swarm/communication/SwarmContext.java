package com.rorm.ai.swarm.communication;

import java.util.List;

/**
 * Shared persistent swarm state. Holds, for every first-level agent
 * across every run, an {@link AgentState} with their chat id, role,
 * schema, agent config, and the growing list of {@link Round}s they
 * have completed. Entries are durable — they live for the lifetime of
 * the underlying store and are not cleared at run end.
 * <p>
 * Population is exclusive to {@link SwarmContextStreamAdvisor}, which
 * attaches only to first-level streaming chat calls. Non-streaming
 * summarizer calls have no advisor and do not appear here.
 * <p>
 * Read by {@link SwarmCommunicationBuffer} when polling peers.
 */
public interface SwarmContext {

    /**
     * Records an agent. Idempotent: re-registering the same {@code
     * chatId} updates role/schema/agentConfig in place; rounds already
     * appended are preserved.
     */
    void register(AgentRegistration registration);

    /**
     * Appends one completed round to the agent's state. Called by the
     * stream advisor as each round closes.
     */
    void appendRound(String runId, String chatId, Round round);

    /**
     * Every entry registered for the run, in registration order.
     */
    List<AgentState> list(String runId);
}
