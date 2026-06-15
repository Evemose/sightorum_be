package com.rorm.client.chat.dto;

import com.rorm.client.chat.session.AnalysisKind;
import com.rorm.client.chat.session.AnalysisStatus;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * Flat projection of a SessionAnalysis used by the home and chat list views.
 * `schemaName` is denormalized from the owning Session.
 */
public record AnalysisListItemDTO(
    String id,
    @Nullable String schemaName,
    AnalysisKind kind,
    AnalysisStatus status,
    @Nullable String query,
    Instant startedAt,
    @Nullable Instant completedAt
) {}
