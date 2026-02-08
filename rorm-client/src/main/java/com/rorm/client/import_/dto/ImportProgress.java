package com.rorm.client.import_.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.rorm.client.import_.dto.ImportProgress.Event.ChunkFailed;
import com.rorm.client.import_.dto.ImportProgress.Event.ChunkProcessed;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public sealed interface ImportProgress permits
    ImportProgress.Progress,
    ImportProgress.JobComplete,
    ImportProgress.Error {

    UUID jobId();

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = ChunkProcessed.class, name = "chunk_processed"),
        @JsonSubTypes.Type(value = ChunkFailed.class, name = "chunk_failed")
    })
    sealed interface Event {
        Instant timestamp();

        record ChunkProcessed(long chunkNumber, long rowsWritten, List<String> warnings,
                              Instant timestamp) implements Event {}

        record ChunkFailed(long chunkNumber, String errorMessage, Instant timestamp) implements Event {}
    }

    record Progress(
        UUID jobId,
        long totalRows,
        long rowsProcessed,
        long rowsFailed,
        double progressPercent,
        Event latestEvent
    ) implements ImportProgress {}

    record JobComplete(
        UUID jobId,
        long totalRows
    ) implements ImportProgress {}

    record Error(
        UUID jobId,
        String errorMessage
    ) implements ImportProgress {}
}
