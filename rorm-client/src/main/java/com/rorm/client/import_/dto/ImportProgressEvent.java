package com.rorm.client.import_.dto;

import java.util.UUID;

public sealed interface ImportProgressEvent permits
    ImportProgressEvent.Chunk,
    ImportProgressEvent.StepComplete,
    ImportProgressEvent.JobComplete,
    ImportProgressEvent.Error {

    UUID jobId();

    record Chunk(
        UUID jobId,
        String rootName,
        long processedRows,
        long totalRows,
        double progressPercent
    ) implements ImportProgressEvent {}

    record StepComplete(
        UUID jobId,
        String rootName,
        long totalRows
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
