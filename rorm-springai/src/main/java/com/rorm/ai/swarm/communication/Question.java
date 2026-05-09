package com.rorm.ai.swarm.communication;

/**
 * Question one swarm agent puts to another role.
 *
 * @param role role of the agent(s) being asked, e.g. {@code "scout"}
 * @param text the question
 */
public record Question(String role, String text) {
}
