package com.rorm.client.chat.dto;

import java.time.Instant;
import java.util.UUID;

public record ChatSessionResponse(
    UUID id,
    String status,
    String schemaName,
    UUID parentSessionId,
    Instant createdAt
) {}
