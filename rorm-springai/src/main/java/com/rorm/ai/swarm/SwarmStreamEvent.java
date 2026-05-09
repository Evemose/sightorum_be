package com.rorm.ai.swarm;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.rorm.ai.chat.StreamToken;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = SwarmStreamEvent.AgentStarted.class, name = "AGENT_STARTED"),
    @JsonSubTypes.Type(value = SwarmStreamEvent.AgentToken.class, name = "AGENT_TOKEN"),
    @JsonSubTypes.Type(value = SwarmStreamEvent.AgentFinished.class, name = "AGENT_FINISHED"),
    @JsonSubTypes.Type(value = SwarmStreamEvent.AgentQuestion.class, name = "AGENT_QUESTION"),
    @JsonSubTypes.Type(value = SwarmStreamEvent.AgentAnswer.class, name = "AGENT_ANSWER"),
    @JsonSubTypes.Type(value = SwarmStreamEvent.AgentProgress.class, name = "AGENT_PROGRESS"),
    @JsonSubTypes.Type(value = SwarmStreamEvent.RunCompleted.class, name = "RUN_COMPLETED")
})
public sealed interface SwarmStreamEvent {

    /**
     * Run-scoped, replay-deterministic identity used by
     * {@link SwarmEventBus} implementations to drop repeat publishes
     * caused by mid-stream interruption + retry, parent-invocation
     * replay outside a journal context, or any other source of
     * duplicate emission. Two emissions with the same {@code dedupKey}
     * for the same {@code runId} are guaranteed to represent the same
     * logical event and the bus must emit only the first one.
     */
    String dedupKey();

    record AgentStarted(EventId eventId, String kind) implements SwarmStreamEvent {
        @Override
        public String dedupKey() {
            return "started:" + eventId.token();
        }
    }

    record AgentToken(EventId eventId, int sequence, StreamToken token) implements SwarmStreamEvent {
        @Override
        public String dedupKey() {
            return "token:" + eventId.token() + ":" + sequence;
        }
    }

    record AgentFinished(EventId eventId, String kind, String rawResponse, Object output) implements SwarmStreamEvent {
        @Override
        public String dedupKey() {
            return "finished:" + eventId.token();
        }
    }

    record AgentQuestion(EventId eventId, int sequence, String fromRole, String toRole,
                         String text) implements SwarmStreamEvent {
        @Override
        public String dedupKey() {
            return "question:" + eventId.token() + ":" + sequence;
        }
    }

    record AgentAnswer(EventId eventId, int sequence, String fromRole, String toRole,
                       String text) implements SwarmStreamEvent {
        @Override
        public String dedupKey() {
            return "answer:" + eventId.token() + ":" + sequence;
        }
    }

    /**
     * User-friendly progress narration emitted once per assistant round
     * (closed by either a tool-call boundary or stream completion). The
     * paraphrase is produced by a lightweight model from the round's
     * thinking and text, written in a configured character voice, with
     * all earlier rounds of the same agent passed back in for narrative
     * continuity.
     *
     * @param roundIndex zero-based position of this round in the agent's
     *                   stream — also used as the dedup discriminator so
     *                   replay-issued repeats collapse on the bus.
     */
    record AgentProgress(EventId eventId, int roundIndex, String message) implements SwarmStreamEvent {
        @Override
        public String dedupKey() {
            return "progress:" + eventId.token() + ":" + roundIndex;
        }
    }

    record RunCompleted() implements SwarmStreamEvent {
        @Override
        public String dedupKey() {
            return "run-completed";
        }
    }
}
