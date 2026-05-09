package com.rorm.ai.swarm.executor;

/**
 * One captured round of an agent's streamed activity, in the form
 * passed to {@link ProgressExecutor}. A round is the slice between
 * tool-call boundaries (or between the last tool call and stream
 * completion for the final answer round). Both fields are verbatim
 * from the source stream — the executor's paraphraser is what turns
 * them into user-facing prose.
 *
 * @param thinking the round's accumulated assistant thinking; may be empty
 * @param text     the round's accumulated assistant text; may be empty
 */
public record ProgressRound(
    String thinking,
    String text
) {
}
