package com.rorm.dataimport.pipeline;

import java.time.Instant;
import java.util.List;

public sealed interface ImportEvent {

    Long jobId();

    Instant timestamp();

    record ChunkProcessed(Long jobId, long chunkNumber, long rowsWritten, List<String> warnings,
                          Instant timestamp) implements ImportEvent {}

    record ChunkFailed(Long jobId, long chunkNumber, Throwable error, Instant timestamp) implements ImportEvent {}

}
