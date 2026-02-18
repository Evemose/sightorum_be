package com.rorm.client.research.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rorm.client.research.ResearchNodeType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "status")
@JsonSubTypes({
    @JsonSubTypes.Type(value = ResearchNodeResponse.PendingNodeResponse.class, name = "PENDING"),
    @JsonSubTypes.Type(value = ResearchNodeResponse.CompletedNodeResponse.class, name = "COMPLETED"),
    @JsonSubTypes.Type(value = ResearchNodeResponse.FailedNodeResponse.class, name = "FAILED")
})

public sealed interface ResearchNodeResponse permits
    ResearchNodeResponse.PendingNodeResponse,
    ResearchNodeResponse.CompletedNodeResponse,
    ResearchNodeResponse.FailedNodeResponse {

    ResearchNodeType nodeType();

    ResearchNodeStructuralInfo structural();

    List<UUID> dependencyIds();

    Instant timestamp();

    record PendingNodeResponse(
        ResearchNodeType nodeType,
        ResearchNodeStructuralInfo structural,
        List<UUID> dependencyIds,
        Instant startedAt
    ) implements ResearchNodeResponse {
        @Override
        public Instant timestamp() {
            return startedAt;
        }
    }

    record CompletedNodeResponse(
        UUID id,
        ResearchNodeType nodeType,
        ResearchNodeStructuralInfo structural,
        List<UUID> dependencyIds,
        ObjectNode payload,
        String rawResponse,
        Instant createdAt
    ) implements ResearchNodeResponse {
        @Override
        public Instant timestamp() {
            return createdAt;
        }
    }

    record FailedNodeResponse(
        UUID id,
        ResearchNodeType nodeType,
        ResearchNodeStructuralInfo structural,
        List<UUID> dependencyIds,
        String errorMessage,
        Instant createdAt
    ) implements ResearchNodeResponse {
        @Override
        public Instant timestamp() {
            return createdAt;
        }
    }
}
