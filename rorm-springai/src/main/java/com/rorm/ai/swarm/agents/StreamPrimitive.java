package com.rorm.ai.swarm.agents;

/**
 * Re-stream the first-level agent within an in-progress step. The
 * conversation thread (chat id, advisors, tool context entries, agent
 * config) is held by the producing
 * {@link com.rorm.ai.swarm.executor.StepExecutorSupport StepExecutorSupport};
 * the caller supplies only the new user prompt. Tokens are published
 * to the swarm event bus exactly as in the initial stream and the
 * accumulated raw text is returned.
 */
@FunctionalInterface
public interface StreamPrimitive {

    String stream(String userPrompt);
}
