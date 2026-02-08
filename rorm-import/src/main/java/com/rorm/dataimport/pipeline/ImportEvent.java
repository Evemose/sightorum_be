package com.rorm.dataimport.pipeline.progress;

import java.util.List;

public sealed interface ImportEvent {

    Long jobId();

    record ChunkProcessed(Long jobId, long chunkNumber, List<String> warnings) implements ImportEvent {}

    record ChunkFailed(Long jobId, long chunkNumber, Throwable errorMessage) implements ImportEvent {}

}
