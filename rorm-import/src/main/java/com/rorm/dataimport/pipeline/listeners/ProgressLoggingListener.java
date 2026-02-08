package com.rorm.dataimport.pipeline.progress;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.ChunkListener;
import org.springframework.batch.core.scope.context.ChunkContext;

@Slf4j
public class ProgressLoggingListener implements ChunkListener {

    private long startTime;

    @Override
    public void beforeChunk(ChunkContext context) {
        startTime = System.currentTimeMillis();
        log.debug("Starting chunk for step: {}",
            context.getStepContext().getStepName());
    }

    @Override
    public void afterChunk(ChunkContext context) {
        var stepExecution = context.getStepContext().getStepExecution();
        var duration = System.currentTimeMillis() - startTime;

        log.debug("Chunk completed - Total written: {}, Chunk time: {}ms",
            stepExecution.getWriteCount(),
            duration
        );
    }

    @Override
    public void afterChunkError(ChunkContext context) {
        log.error("Chunk failed for step: {}",
            context.getStepContext().getStepName());
    }

}