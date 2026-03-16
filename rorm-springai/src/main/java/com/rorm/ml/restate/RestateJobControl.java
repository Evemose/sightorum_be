package com.rorm.ml.restate;

import com.rorm.ml.AsyncJobGateway;
import com.rorm.ml.JobControl;
import com.rorm.ml.dto.AsyncJobRequest;
import com.rorm.ml.peristence.MLJobMetadataStore;
import com.rorm.ml.stream.JobEvent;
import com.rorm.ml.stream.JobEventType;
import dev.restate.client.Client;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Restate-backed job control: skip/retry/cancel by resolving or rejecting awakeables.
 */
@Slf4j
@RequiredArgsConstructor
public class RestateJobControl implements JobControl {

    private final AwakeableRegistry awakeableRegistry;
    private final Client restateClient;
    private final MLJobMetadataStore metadataStore;
    private final AsyncJobGateway jobGateway;

    @Override
    public Status status(UUID jobId) {
        var workflowKey = jobId.toString();
        try {
            var output = JobAwaitWorkflowClient.fromClient(restateClient, workflowKey)
                .workflowHandle()
                .getOutput()
                .response();
            return output.isReady() ? Status.COMPLETED : Status.PENDING;
        } catch (Exception e) {
            log.debug("Could not get workflow status for job {}: {}", jobId, e.getMessage());
            return Status.NOT_FOUND;
        }
    }

    @Override
    public void skip(UUID jobId) {
        var awakeableId = awakeableRegistry.getAndRemove(jobId);
        if (awakeableId.isEmpty()) {
            log.warn("Cannot skip job {} — no awakeable registered", jobId);
            return;
        }

        var skipEvent = new JobEvent(
            jobId, JobEventType.JOB_FAILED, Instant.now(),
            0.0, "Skipped by user", Map.of(),
            "Job skipped by user", "USER_SKIPPED", Map.of()
        );

        restateClient.awakeableHandle(awakeableId.get())
            .resolve(JobEvent.class, skipEvent);

        log.info("Job {} skipped by user", jobId);
    }

    @Override
    public UUID retry(UUID jobId) {
        // Cancel current workflow
        cancel(jobId);

        // Look up original request and re-submit
        AsyncJobRequest originalRequest = metadataStore.findRequest(jobId)
            .orElseThrow(() -> new IllegalArgumentException("Job request not found: " + jobId));

        var schema = metadataStore.findSchema(jobId)
            .orElseThrow(() -> new IllegalArgumentException("Job schema not found: " + jobId));
        var newJobId = jobGateway.submit(originalRequest, schema);
        log.info("Job {} retried as new job {}", jobId, newJobId);
        return newJobId;
    }

    @Override
    public void cancel(UUID jobId) {
        // Reject the awakeable so the workflow completes with an error
        var awakeableId = awakeableRegistry.getAndRemove(jobId);
        awakeableId.ifPresent(id -> {
            try {
                restateClient.awakeableHandle(id).reject("Cancelled by user");
            } catch (Exception e) {
                log.debug("Could not reject awakeable for job {}: {}", jobId, e.getMessage());
            }
        });

        log.info("Job {} cancelled", jobId);
    }
}
