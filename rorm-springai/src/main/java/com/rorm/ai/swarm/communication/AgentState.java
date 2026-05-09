package com.rorm.ai.swarm.communication;

import com.rorm.ai.swarm.AgentModelConfig;

import java.util.List;
import java.util.UUID;

/**
 * Persistent state of one agent inside a swarm run: the handles needed
 * to poll them ({@code chatId}, {@code agentConfig}), their position in
 * the swarm DAG ({@code agentToken}, {@code parentTokens}), the input
 * prompt they received, and the running set of {@link Round}s they have
 * completed. Rounds are appended in completion order; the last round on
 * a finished agent is their full final answer.
 *
 * @param chatId       unique conversation id and identity of the agent
 * @param agentToken   graph-identity token (the agent's event id token);
 *                     used by upstream-walks to follow parent links
 * @param parentTokens tokens of every direct parent — empty for roots
 * @param role         agent role
 * @param schema       data schema of the run
 * @param agentConfig  agent's actual config — used verbatim when polled
 * @param inputPrompt  rendered user message the agent received
 * @param rounds       completed rounds in completion order
 */
public record AgentState(
    String chatId,
    UUID agentToken,
    List<UUID> parentTokens,
    String role,
    String schema,
    AgentModelConfig agentConfig,
    String inputPrompt,
    List<Round> rounds
) {

    public AgentState {
        parentTokens = List.copyOf(parentTokens);
        rounds = List.copyOf(rounds);
    }

    /**
     * Snapshot of the agent's working session — the rounds they have
     * completed so far wrapped as a {@link Session}. The last round
     * may be in-flight on a still-running agent.
     */
    public Session currentSession() {
        return new Session(rounds);
    }
}
