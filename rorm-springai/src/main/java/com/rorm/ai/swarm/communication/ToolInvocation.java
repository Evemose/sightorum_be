package com.rorm.ai.swarm.communication;

/**
 * One tool the agent dispatched within a {@link Round}, with both
 * the input arguments the agent supplied and the output the tool
 * returned. Both are JSON strings as the Anthropic SDK exchanged
 * them.
 *
 * @param name   registered tool name
 * @param input  the agent's argument JSON
 * @param output the tool's response JSON
 */
public record ToolInvocation(
    String name,
    String input,
    String output
) {
}
