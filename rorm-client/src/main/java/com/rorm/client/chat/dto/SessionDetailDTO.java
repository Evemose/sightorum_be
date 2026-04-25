package com.rorm.client.chat.dto;

import com.rorm.client.chat.session.AnalysisKind;
import com.rorm.client.chat.session.AnalysisStatus;
import com.rorm.client.import_.dto.ImportJobResponse;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;

public record SessionDetailDTO(
    String id,
    @Nullable String title,
    @Nullable String schemaName,
    Instant createdAt,
    Instant updatedAt,
    List<ChatMessageDTO> messages,
    List<AnalysisRef> analyses,
    List<ImportJobResponse> imports
) {
    public record AnalysisRef(
        String runId,
        AnalysisKind kind,
        @Nullable String query,
        AnalysisStatus status,
        Instant startedAt,
        @Nullable Instant completedAt
    ) {}
}
