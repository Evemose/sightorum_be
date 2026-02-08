package com.rorm.dataimport.pipeline.listeners;

import com.rorm.dataimport.pipeline.ImportEvent;
import com.rorm.dataimport.pipeline.ImportEvent.ChunkFailed;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.ChunkListener;
import org.springframework.batch.core.scope.context.ChunkContext;
import reactor.core.publisher.Sinks;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

@RequiredArgsConstructor
public class ImportEventListener implements ChunkListener {

    public static final String WARNINGS_KEY = "warnings";
    public static final String ROWS_IN_CHUNK_KEY = "rowsInChunk";
    private final Sinks.Many<ImportEvent> eventSink;

    @Override
    @SuppressWarnings({"unchecked"})
    public void afterChunk(ChunkContext context) {
        var jobId = context.getStepContext().getJobInstanceId();
        var execution = context.getStepContext().getStepExecution();
        var chunkNum = execution.getCommitCount() + execution.getRollbackCount();
        var warnings = Objects.requireNonNullElseGet(
            (List<String>) context.getAttribute(WARNINGS_KEY),
            List::<String>of
        );
        var rowsAttr = context.getAttribute(ROWS_IN_CHUNK_KEY);
        var rowsWritten = rowsAttr instanceof Long l ? l : 0L;
        eventSink.tryEmitNext(new ImportEvent.ChunkProcessed(jobId, chunkNum, rowsWritten, warnings, Instant.now()));
    }

    @Override
    public void afterChunkError(ChunkContext context) {
        var exception = context.getStepContext().getStepExecution().getFailureExceptions().stream()
            .findFirst()
            .orElse(new RuntimeException("Unknown error during chunk processing"));
        var jobId = context.getStepContext().getJobInstanceId();
        var execution = context.getStepContext().getStepExecution();
        var chunkNum = execution.getCommitCount() + execution.getRollbackCount();
        eventSink.tryEmitNext(new ChunkFailed(jobId, chunkNum, exception, Instant.now()));
    }
}
