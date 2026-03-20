package com.rorm.ml.peristence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.ml.dto.AsyncJobRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

// REMOVEME
@Component
@RequiredArgsConstructor
public class MLJobMetadataStore {

    private final MLJobRepository repo;
    private final ObjectMapper objectMapper;

    public Optional<MLJobInfo> findByJobId(UUID jobId) {
        return repo.findByJobId(jobId).map(MLJob::toInfo);
    }

    public void persistJob(UUID jobId, AsyncJobRequest request, String schema) {
        repo.save(MLJob.of(jobId, request.jobType(), schema, request, objectMapper));
    }

    public <T extends AsyncJobRequest> Optional<T> findRequest(UUID jobId) {
        return repo.findByJobId(jobId).map(job -> job.requestAs(objectMapper));
    }

    public Optional<String> findSchema(UUID jobId) {
        return repo.findByJobId(jobId).map(MLJob::getSchema);
    }
}
