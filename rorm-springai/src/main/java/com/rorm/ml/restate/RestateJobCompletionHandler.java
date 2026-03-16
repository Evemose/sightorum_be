package com.rorm.ml.restate;

import com.rorm.ml.peristence.MLJobInfo;
import com.rorm.ml.stream.JobCompletionHandler;
import com.rorm.ml.stream.JobEvent;
import dev.restate.client.Client;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Routes job completion events to Restate awakeables instead of in-memory futures.
 * <p>
 * Both success and failure resolve the awakeable (not reject) — the {@link JobEvent} carries
 * status info that the caller ({@code ExecutorSwarmAgent}) inspects to build the phase-2 prompt.
 */
@Slf4j
@RequiredArgsConstructor
public class RestateJobCompletionHandler implements JobCompletionHandler {

    private final AwakeableRegistry awakeableRegistry;
    private final Client restateClient;

    @Override
    public void onJobSuccess(MLJobInfo jobInfo, JobEvent event) {
        resolveAwakeable(jobInfo, event);
    }

    private void resolveAwakeable(MLJobInfo jobInfo, JobEvent event) {
        var maybeId = awakeableRegistry.getAndRemove(jobInfo.jobId());
        if (maybeId.isEmpty()) {
            log.warn("No awakeable registered for job {} — event may have arrived before workflow started", jobInfo.jobId());
            // Retry with short backoff for the race condition window
            for (int attempt = 1; attempt <= 3; attempt++) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                maybeId = awakeableRegistry.getAndRemove(jobInfo.jobId());
                if (maybeId.isPresent()) {
                    break;
                }
            }
        }

        maybeId.ifPresentOrElse(
            awakeableId -> {
                log.info("Resolving Restate awakeable for job {}", jobInfo.jobId());
                restateClient.awakeableHandle(awakeableId)
                    .resolve(JobEvent.class, event);
            },
            () -> log.error("Failed to find awakeable for job {} after retries — job result lost", jobInfo.jobId())
        );
    }

    @Override
    public void onJobFailure(MLJobInfo jobInfo, JobEvent event) {
        // Resolve, not reject — the workflow caller needs the JobEvent to inspect isFailed()
        resolveAwakeable(jobInfo, event);
    }

    @Override
    public void onJobProgress(MLJobInfo jobInfo, JobEvent event) {
        log.debug("Job progress (Restate mode) for job {}: {}%",
            jobInfo.jobId(),
            String.format("%.1f", event.progress() * 100));
    }
}
