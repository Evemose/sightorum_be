package com.rorm.ml.peristence;

import com.rorm.ml.AsyncJobGateway;
import com.rorm.ml.MlTrainingService;
import com.rorm.ml.dto.AsyncJobRequest;
import com.rorm.ml.stream.JobFutureRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@ConditionalOnProperty(name = "rorm.ml.durable-execution", havingValue = "false", matchIfMissing = true)
@RequiredArgsConstructor
public class MLPersistence implements AsyncJobGateway {

    private final MLJobMetadataStore metadataStore;
    private final JobFutureRegistry futureRegistry;
    private final MlTrainingService mlTrainingService;

    @Override
    public UUID submit(AsyncJobRequest request, String schema) {
        var jobId = mlTrainingService.submit(request);
        metadataStore.persistJob(jobId, request, schema);
        futureRegistry.register(jobId);
        return jobId;
    }
}
