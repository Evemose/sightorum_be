package com.rorm.ai.swarm.executor;

import com.rorm.ai.swarm.EventId;

import java.util.List;

/**
 * Serializable input envelope for {@link ProgressExecutor}. One round of an
 * agent's streamed thinking + text becomes one of these, dispatched as a
 * Restate sub-invocation keyed by a stable content hash so the parent's
 * replay deduplicates without re-executing the paraphrase or re-publishing
 * the {@code AgentProgress} event.
 * <p>
 * {@link #priorRounds} carries every earlier round of this same agent that
 * the advisor has already dispatched, in order. The executor uses them to
 * render the current round in narrative continuity instead of treating
 * every round as the opening line.
 *
 * @param runId       parent swarm run identifier (event-bus topic)
 * @param eventId     parent agent's event id; the published progress event
 *                    carries this same id so the FE can attach the narration
 *                    to the active agent panel
 * @param schema      schema for the chat request envelope; resolved into a
 *                    {@code ModelSpace} on the executor side
 * @param character   paraphraser character/voice (from
 *                    {@code DurableSwarmConfig.progressCharacter}); may be empty
 * @param priorRounds earlier rounds already dispatched for this agent, in
 *                    completion order; empty for the first round
 * @param thinking    the round's accumulated assistant thinking (may be empty)
 * @param text        the round's accumulated assistant text (may be empty)
 */
public record ProgressInput(
    String runId,
    EventId eventId,
    String schema,
    String character,
    List<ProgressRound> priorRounds,
    String thinking,
    String text
) {

    public ProgressInput {
        priorRounds = List.copyOf(priorRounds);
    }
}
