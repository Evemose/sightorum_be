package com.rorm.client.import_;

import com.rorm.client.import_.dto.ImportProgress;
import com.rorm.client.import_.mapper.CoercionStrategyMapper;
import com.rorm.client.import_.mapper.DetectionOverrideMapper;
import com.rorm.client.metamodel.MetamodelService;
import com.rorm.dataimport.override.DetectionOverride;
import com.rorm.dataimport.pipeline.*;
import com.rorm.dataimport.source.CsvDataSource;
import com.rorm.dataimport.source.ImportDataSource;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

@Slf4j
@Component
@RequiredArgsConstructor
public class ImportJobWorker {

    private final ImportJobQueue jobQueue;
    private final ImportJobRepository jobRepository;
    private final DataImportPipeline importPipeline;
    private final ModelSpaceDetector modelSpaceDetector;
    private final MetamodelService metamodelService;
    private final ImportProgressPublisher progressPublisher;
    private final DetectionOverrideMapper detectionOverrideMapper;
    private final CoercionStrategyMapper coercionStrategyMapper;
    @Lazy
    private final ImportJobWorker self;
    @ImportTaskExecutor
    private final AsyncTaskExecutor taskExecutor;

    private final AtomicBoolean running = new AtomicBoolean(true);

    @PostConstruct
    public void start() {
        // Start worker threads
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

            // Load all files from upload directory
            var uploadDir = Path.of(payload.uploadDir());
            var dataSources = loadDataSources(uploadDir);

            log.info("Loaded {} data source(s) from upload: {}", dataSources.size(), jobId);

            // Parse DTOs to domain objects
            var overridesByRoot = payload.overridesByRoot() != null
                ? detectionOverrideMapper.toDetectionOverridesMap(payload.overridesByRoot())
                : Collections.<String, List<DetectionOverride>>emptyMap();

            // Detect schema with overrides
            var detectedSchema = modelSpaceDetector.detect(
                dataSources,
                overridesByRoot,
                payload.listSeparator()
            );

            // Convert coercion config DTOs to domain strategies
            var coercionStrategies = coercionStrategyMapper.toCoercionStrategies(payload.coercionConfigs());

            // Create import request with coercion strategies
            var request = new ImportRequest(
                payload.targetSchema(),
                dataSources,
                detectedSchema,
                payload.chunkSize(),
                coercionStrategies
            );

            // Execute import
            var result = importPipeline.importData(request);

            // Subscribe to progress flux - publish SSE events per emission, block until done
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

                    // Append event to job entity for persistence
                    job.appendEvent(toEventLog(importEvent));
                })
                .blockLast();

            var totalRows = finalProgress != null ? finalProgress.rowsProcessed() : 0L;

            // Save metamodel
            metamodelService.saveMetamodel(payload.targetSchema(), result.modelSpace());

            // Mark complete
            job.markCompleted(totalRows);
            jobRepository.save(job);

            // Publish completion
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

    private List<ImportDataSource> loadDataSources(Path uploadDir) throws IOException {
        try (Stream<Path> files = Files.list(uploadDir)) {
            return files
                .filter(Files::isRegularFile)
                .map(this::createDataSource)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();
        }
    }

    private Optional<ImportDataSource> createDataSource(Path path) {
        var name = path.getFileName().toString().toLowerCase();

        if (name.endsWith(".csv") || name.endsWith(".tsv") || name.endsWith(".txt")) {
            return Optional.of(new CsvDataSource(path));
        } else if (name.endsWith(".json")) {
            return Optional.of(new com.rorm.dataimport.hierarchical.JsonDataSource(path));
        } else if (name.endsWith(".yaml") || name.endsWith(".yml")) {
            return Optional.of(new com.rorm.dataimport.hierarchical.YamlDataSource(path));
        } else {
            log.warn("Unsupported file type: {}", name);
            return Optional.empty();
        }
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
