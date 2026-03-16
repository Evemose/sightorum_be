package com.rorm.ml.restate;

import com.rorm.ml.JobResultAwaiter;
import com.rorm.ml.stream.JobEvent;
import dev.restate.client.Client;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Awaits job completion by querying the Restate workflow's output.
 * <p>
 * The workflow was started by {@link RestateAsyncJobGateway}. When the awakeable is resolved
 * (by {@link RestateJobCompletionHandler}), the workflow completes and its output becomes available.
 */
@Slf4j
@RequiredArgsConstructor
public class RestateJobResultAwaiter implements JobResultAwaiter {

    private final Client restateClient;

    @Override
    @Nullable
    public JobEvent await(UUID jobId, long timeout, TimeUnit unit) throws InterruptedException {
        var workflowKey = jobId.toString();
        try {
            var output = JobAwaitWorkflowClient.fromClient(restateClient, workflowKey)
                .workflowHandle()
                .getOutput()
                .response();

            if (output.isReady()) {
                return output.getValue();
            }

            // Output not ready yet — poll with backoff until timeout
            var deadlineMs = System.currentTimeMillis() + unit.toMillis(timeout);
            while (System.currentTimeMillis() < deadlineMs) {
                Thread.sleep(2000); // 2s poll interval

                output = JobAwaitWorkflowClient.fromClient(restateClient, workflowKey)
                    .workflowHandle()
                    .getOutput()
                    .response();

                if (output.isReady()) {
                    return output.getValue();
                }
            }

            log.warn("Job {} timed out after {} {}", jobId, timeout, unit);
            return null;

        } catch (Exception e) {
            if (e instanceof InterruptedException ie) {
                throw ie;
            }
            log.warn("Error awaiting Restate workflow for job {}: {}", jobId, e.getMessage());
            return null;
        }
    }

    @Override
    public void remove(UUID jobId) {
        // No-op — Restate manages workflow lifecycle
    }
}
