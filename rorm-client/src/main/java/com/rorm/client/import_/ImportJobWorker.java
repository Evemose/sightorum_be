package com.rorm.client.import_;

import com.rorm.client.import_.dto.ImportProgress;
import com.rorm.client.import_.mapper.CoercionStrategyMapper;
import com.rorm.client.import_.mapper.DetectionOverrideMapper;
import com.rorm.client.metamodel.MetamodelService;
import com.rorm.dataimport.override.DetectionOverride;
import com.rorm.dataimport.pipeline.*;
import com.rorm.dataimport.type.InvalidValueCoercionStrategy;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.retry.RetryException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
public class ImportJobWorker {

    private final ImportJobQueue jobQueue;
    private final ImportJobRepository jobRepository;
    private final MetamodelService metamodelService;
    private final ImportProgressPublisher progressPublisher;
    private final ImportPipelineExecutor pipelineExecutor;
    private final CategoricalValueRefresher categoricalValueRefresher;
    private final DetectionOverrideMapper detectionOverrideMapper;
    private final CoercionStrategyMapper coercionStrategyMapper;
    @Lazy
    private final ImportJobWorker self;
    @ImportTaskExecutor
    private final AsyncTaskExecutor taskExecutor;

    private final AtomicBoolean running = new AtomicBoolean(true);

    @PostConstruct
    public void start() {
        for (int i = 0; i < 2; i++) {
            taskExecutor.execute(this::processJobs);
        }
        log.info("Import job workers started");
    }

    private void processJobs() {
        while (running.get()) {
            try {
                var payload = jobQueue.dequeue(Duration.ofSeconds(5));
                if (payload != null) {
                    self.processJob(payload);
                }
            } catch (Exception e) {
                log.error("Error processing import job", e);
            }
        }
    }

    @Retryable(retryFor = Exception.class, backoff = @Backoff(delay = 5000, multiplier = 2))
    public void processJob(ImportJobQueue.ImportJobPayload payload) {
        var jobId = payload.jobId();
        log.info("Processing import job: {}", jobId);

        var job = jobRepository.findById(jobId).orElse(null);
        if (job == null) {
            log.warn("Import job not found, will attempt retry: {}", jobId);
            throw new RetryException("Import job not found: " + jobId);
        }

        try {
            job.markInProgress();
            jobRepository.save(job);

            var pipelineRequest = toPipelineRequest(payload);
            var result = pipelineExecutor.execute(pipelineRequest);

            var finalProgress = result.progress()
                .doOnNext(progress -> {
                    var importEvent = progress.events().getLast();
                    var latestEvent = toSseEvent(importEvent);
                    progressPublisher.publish(new ImportProgress.Progress(
                        jobId,
                        progress.totalRows(),
                        progress.rowsProcessed(),
                        progress.rowsFailed(),
                        progress.progressPercent(),
                        latestEvent
                    ));
                    job.appendEvent(toEventLog(importEvent));
                })
                .blockLast();

            var totalRows = finalProgress != null ? finalProgress.rowsProcessed() : 0L;

            var refreshedModelSpace = categoricalValueRefresher.refresh(
                payload.targetSchema(), result.modelSpace());
            metamodelService.saveMetamodel(payload.targetSchema(), refreshedModelSpace);

            job.markCompleted(totalRows);
            jobRepository.save(job);

            progressPublisher.publish(new ImportProgress.JobComplete(jobId, totalRows));
            progressPublisher.complete(jobId);

            log.info("Import job completed: {} - {} rows", jobId, totalRows);

        } catch (Exception e) {
            log.error("Import job failed: {}", jobId, e);
            job.markFailed(e.getMessage());
            jobRepository.save(job);
            progressPublisher.publish(new ImportProgress.Error(jobId, e.getMessage()));
            progressPublisher.error(jobId, e.getMessage());
        }
    }

    private ImportPipelineRequest toPipelineRequest(ImportJobQueue.ImportJobPayload payload) {
        Map<String, List<DetectionOverride>> overridesByRoot = payload.overridesByRoot() != null
            ? detectionOverrideMapper.toDetectionOverridesMap(payload.overridesByRoot())
            : Collections.emptyMap();

        Map<ImportRequest.AttributeKey, InvalidValueCoercionStrategy> coercionStrategies =
            payload.coercionConfigs() != null
                ? coercionStrategyMapper.toCoercionStrategies(payload.coercionConfigs())
                : Collections.emptyMap();

        return new ImportPipelineRequest(
            Path.of(payload.uploadDir()),
            payload.targetSchema(),
            payload.chunkSize(),
            payload.listSeparator(),
            overridesByRoot,
            coercionStrategies
        );
    }

    private static ImportProgress.Event toSseEvent(ImportEvent event) {
        return switch (event) {
            case ImportEvent.ChunkProcessed cp -> new ImportProgress.Event.ChunkProcessed(
                cp.chunkNumber(), cp.rowsWritten(), cp.warnings(), cp.timestamp()
            );
            case ImportEvent.ChunkFailed cf -> new ImportProgress.Event.ChunkFailed(
                cf.chunkNumber(), cf.error().getMessage(), cf.timestamp()
            );
        };
    }

    private static ImportEventLog toEventLog(ImportEvent event) {
        return switch (event) {
            case ImportEvent.ChunkProcessed cp -> ImportEventLog.chunkProcessed(
                cp.chunkNumber(), cp.rowsWritten(), cp.warnings(), cp.timestamp()
            );
            case ImportEvent.ChunkFailed cf -> ImportEventLog.chunkFailed(
                cf.chunkNumber(), cf.error().getMessage(), cf.timestamp()
            );
        };
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        log.info("Import job workers stopping");
    }
}
