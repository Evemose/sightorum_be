package com.rorm.client.import_.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public sealed interface ImportProgressEvent permits
    ImportProgressEvent.Progress,
    ImportProgressEvent.JobComplete,
    ImportProgressEvent.Error {

    UUID jobId();

    sealed interface Event {
        Instant timestamp();

        record ChunkProcessed(long chunkNumber, long rowsWritten, List<String> warnings, Instant timestamp) implements Event {}

        record ChunkFailed(long chunkNumber, String errorMessage, Instant timestamp) implements Event {}
    }

    record Progress(
        UUID jobId,
        long totalRows,
        long rowsProcessed,
        long rowsFailed,
        double progressPercent,
        Event latestEvent
    ) implements ImportProgressEvent {}

    record JobComplete(
        UUID jobId,
        long totalRows
    ) implements ImportProgressEvent {}

    record Error(
        UUID jobId,
        String errorMessage
    ) implements ImportProgressEvent {}
}
