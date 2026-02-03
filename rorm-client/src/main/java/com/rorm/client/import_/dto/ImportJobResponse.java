package com.rorm.client.import_.dto;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

public record ImportJobResponse(
    UUID id,
    String status,
    String targetSchema,
    @Nullable Long totalRows,
    @Nullable Long processedRows,
    @Nullable String errorMessage,
    Instant startedAt,
    @Nullable Instant completedAt
) {}
