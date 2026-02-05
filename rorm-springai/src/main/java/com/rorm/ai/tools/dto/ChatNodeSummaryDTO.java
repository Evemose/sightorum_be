package com.rorm.ai.tools.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Summary DTO for chat nodes used when listing chat history.
 * Contains only essential information for navigation.
 */
public record ChatNodeSummaryDTO(
    UUID id,
    Instant createdAt,
    String type,
    String summary
) {}
