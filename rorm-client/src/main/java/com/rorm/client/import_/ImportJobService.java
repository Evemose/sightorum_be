package com.rorm.client.import_;

import com.rorm.client.import_.dto.*;
import com.rorm.client.import_.mapper.DetectedSchemaMapper;
import com.rorm.client.import_.mapper.ImportJobMapper;
import com.rorm.client.import_.mapper.SchemaOverrideMapper;
import com.rorm.dataimport.pipeline.ModelSpaceDetector;
import com.rorm.dataimport.source.CsvDataSource;
import com.rorm.dataimport.source.ImportDataSource;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImportJobService {

    private final TempFileStorage tempFileStorage;
    private final ImportJobRepository jobRepository;
    private final ImportJobQueue jobQueue;
    private final ModelSpaceDetector modelSpaceDetector;
    private final DetectedSchemaMapper detectedSchemaMapper;
    private final SchemaOverrideMapper schemaOverrideMapper;
    private final ImportJobMapper importJobMapper;

    public UploadResponse uploadFiles(List<MultipartFile> files) throws IOException {
        var stored = tempFileStorage.storeFiles(files);

        var fileInfos = stored.files().stream()
            .map(f -> new UploadResponse.FileInfo(f.originalName(), f.size()))
            .toList();

        return new UploadResponse(stored.uploadId(), fileInfos);
    }

    public DetectedSchemaResponse detectSchema(DetectSchemaRequest request) throws IOException {
        var filePaths = tempFileStorage.listFiles(request.uploadId());

        var dataSources = new ArrayList<ImportDataSource>();
        for (var path : filePaths) {
            dataSources.add(new CsvDataSource(path));
        }

        Map<String, List<com.rorm.dataimport.override.SchemaOverride>> overridesByRoot =
            request.overridesByRoot() != null
                ? schemaOverrideMapper.toSchemaOverridesMap(request.overridesByRoot())
                : Map.of();

        var detectedSchema = modelSpaceDetector.detect(
            dataSources,
            overridesByRoot,
            request.listSeparator()
        );

        return detectedSchemaMapper.toResponse(detectedSchema);
    }

    @Transactional
    public ImportJobResponse startImport(StartImportRequest request) {
        var uploadDir = tempFileStorage.getUploadDir(request.uploadId()).toString();

        var job = new ImportJob(request.targetSchema(), uploadDir);
        var savedJob = jobRepository.save(job);

        var payload = new ImportJobQueue.ImportJobPayload(
            savedJob.getId(),
            uploadDir,
            request.targetSchema(),
            request.chunkSize(),
            ",",
            request.overridesByRoot() != null ? request.overridesByRoot() : Map.of()
        );

        jobQueue.enqueue(payload);

        return importJobMapper.toResponse(savedJob);
    }

    @Transactional(readOnly = true)
    public ImportJobResponse getJob(UUID jobId) {
        var job = jobRepository.findById(jobId)
            .orElseThrow(() -> new EntityNotFoundException("Import job not found: " + jobId));
        return importJobMapper.toResponse(job);
    }

    @Transactional(readOnly = true)
    public List<ImportJobResponse> listJobs() {
        return importJobMapper.toResponses(jobRepository.findAll(Sort.by("startedAt").descending()));
    }
}
