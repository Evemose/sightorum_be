package com.rorm.client.chat.dto;

import com.rorm.client.chat.session.AnalysisKind;
import com.rorm.client.chat.session.AnalysisStatus;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * Pure metadata view of a run. Events are served exclusively through
 * {@code GET /research/{runId}/stream}, which transparently replays from
 * storage when the live Redis stream is gone.
 */
public record AnalysisDetailDTO(
    String runId,
    String sessionId,
    AnalysisKind kind,
    @Nullable String query,
    AnalysisStatus status,
    Instant startedAt,
    @Nullable Instant completedAt,
    @Nullable String errorMessage
) {}
