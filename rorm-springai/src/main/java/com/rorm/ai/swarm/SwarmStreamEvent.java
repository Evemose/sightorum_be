package com.rorm.ai.swarm;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.rorm.ai.chat.StreamToken;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = SwarmStreamEvent.AgentStarted.class, name = "AGENT_STARTED"),
    @JsonSubTypes.Type(value = SwarmStreamEvent.AgentToken.class, name = "AGENT_TOKEN"),
    @JsonSubTypes.Type(value = SwarmStreamEvent.AgentFinished.class, name = "AGENT_FINISHED"),
    @JsonSubTypes.Type(value = SwarmStreamEvent.RunCompleted.class, name = "RUN_COMPLETED")
})
public sealed interface SwarmStreamEvent {

    record AgentStarted(EventId eventId, String kind) implements SwarmStreamEvent {}

    record AgentToken(EventId eventId, StreamToken token) implements SwarmStreamEvent {}

    record AgentFinished(EventId eventId, String kind, String rawResponse, Object output) implements SwarmStreamEvent {}

    record RunCompleted() implements SwarmStreamEvent {}
}
