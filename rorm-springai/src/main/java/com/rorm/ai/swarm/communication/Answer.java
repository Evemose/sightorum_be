package com.rorm.ai.swarm.communication;

/**
 * One peer's answer to a {@link Question}. The buffer returns one
 * {@code Answer} per peer that matched the question's target role and
 * was not the asker.
 *
 * @param role role of the agent that answered
 * @param text the agent's response text
 */
public record Answer(String role, String text) {
}
