package com.rorm.ai.swarm.communication;

import java.util.List;

/**
 * One completed assistant round in an agent's turn: the thinking the
 * agent did, the text it emitted, and every tool it dispatched in that
 * round (with both input and output). A round is only appended to an
 * {@link AgentState} once its tool exchanges have all returned — an
 * in-flight round is not yet a {@code Round}.
 *
 * @param thinking        thinking text for the round; empty when the
 *                        model did not produce thinking
 * @param text            assistant text emitted in this round
 * @param toolInvocations tools dispatched in this round; empty for a
 *                        final answer round with no tool work
 */
public record Round(
    String thinking,
    String text,
    List<ToolInvocation> toolInvocations
) {

    public Round {
        toolInvocations = List.copyOf(toolInvocations);
    }
}
