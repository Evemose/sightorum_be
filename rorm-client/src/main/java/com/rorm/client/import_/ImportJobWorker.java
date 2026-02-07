package com.rorm.client.import_;

import com.rorm.client.import_.dto.ImportProgressEvent;
import com.rorm.client.import_.mapper.DetectionOverrideMapper;
import com.rorm.client.metamodel.MetamodelService;
import com.rorm.dataimport.override.DetectionOverride;
import com.rorm.dataimport.pipeline.DataImportPipeline;
import com.rorm.dataimport.pipeline.ImportRequest;
import com.rorm.dataimport.pipeline.ModelSpaceDetector;
import com.rorm.dataimport.pipeline.RormImportAutoConfiguration.ImportTaskExecutor;
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
import java.util.Map;
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

            // Parse DTOs to domain objects using MapStruct
            Map<String, List<DetectionOverride>> overridesByRoot = payload.overridesByRoot() != null
                ? detectionOverrideMapper.toDetectionOverridesMap(payload.overridesByRoot())
                : Collections.emptyMap();

            // Detect schema with overrides
            var detectedSchema = modelSpaceDetector.detect(
                dataSources,
                overridesByRoot,
                payload.listSeparator()
            );

            // Create import request
            var request = new ImportRequest(
                payload.targetSchema(),
                dataSources,
                detectedSchema,
                payload.chunkSize()
            );

            // Execute import
            var result = importPipeline.importData(request);

            // Save metamodel
            metamodelService.saveMetamodel(payload.targetSchema(), result.modelSpace());

            // Mark complete
            job.markCompleted(result.totalRowsImported());
            jobRepository.save(job);

            // Publish completion
            progressPublisher.publish(new ImportProgressEvent.JobComplete(jobId, result.totalRowsImported()));
            progressPublisher.complete(jobId);

            log.info("Import job completed: {} - {} rows", jobId, result.totalRowsImported());

        } catch (Exception e) {
            log.error("Import job failed: {}", jobId, e);

            job.markFailed(e.getMessage());
            jobRepository.save(job);

            progressPublisher.publish(new ImportProgressEvent.Error(jobId, e.getMessage()));
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

    @PreDestroy
    public void stop() {
        running.set(false);
        log.info("Import job workers stopping");
    }
}
