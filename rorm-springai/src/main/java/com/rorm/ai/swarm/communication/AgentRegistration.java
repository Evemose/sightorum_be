package com.rorm.ai.swarm.communication;

import com.rorm.ai.swarm.AgentModelConfig;

import java.util.List;
import java.util.UUID;

/**
 * Caller-supplied form for {@link SwarmContext#register}. Carries the
 * agent's identifying handles, the user prompt they received, and the
 * tokens of every agent whose output fed into theirs — needed so peers
 * can walk upstream in the swarm DAG.
 *
 * @param runId        active swarm run identifier
 * @param chatId       unique conversation id and stable identity
 * @param agentToken   graph-identity token (the agent's event id token)
 * @param parentTokens tokens of every direct parent — empty for root
 *                     agents (scout, domain researcher)
 * @param role         agent role, e.g. {@code "scout"}
 * @param schema       data schema this run operates on
 * @param agentConfig  agent's actual config — its system prompt and
 *                     model are sent verbatim on every peer poll
 * @param inputPrompt  rendered user message the agent received
 */
public record AgentRegistration(
    String runId,
    String chatId,
    UUID agentToken,
    List<UUID> parentTokens,
    String role,
    String schema,
    AgentModelConfig agentConfig,
    String inputPrompt
) {

    public AgentRegistration {
        parentTokens = List.copyOf(parentTokens);
    }
}
