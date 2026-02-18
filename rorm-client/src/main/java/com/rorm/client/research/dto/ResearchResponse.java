package com.rorm.client.research.dto;

import com.rorm.client.research.ResearchStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ResearchResponse(
    UUID id,
    String swarmId,
    String schemaName,
    ResearchStatus status,
    List<ResearchNodeResponse> nodes,
    Instant createdAt,
    Instant updatedAt
) {}
