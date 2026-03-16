package com.rorm.ml.restate;

import com.rorm.ml.AsyncJobGateway;
import com.rorm.ml.MlTrainingService;
import com.rorm.ml.dto.AsyncJobRequest;
import com.rorm.ml.peristence.MLJobMetadataStore;
import dev.restate.client.Client;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
public class RestateAsyncJobGateway implements AsyncJobGateway {

    private final MLJobMetadataStore metadataStore;
    private final MlTrainingService mlTrainingService;
    private final Client restateClient;

    @Override
    public UUID submit(AsyncJobRequest request, String schema) {
        var jobId = mlTrainingService.submit(request);
        metadataStore.persistJob(jobId, request, schema);

        var workflowKey = jobId.toString();
        JobAwaitWorkflowClient.fromClient(restateClient, workflowKey)
            .submit(workflowKey);

        log.info("Submitted durable job await workflow for job {}", jobId);
        return jobId;
    }
}
