package com.rorm.ml.restate;

import com.rorm.durable.Awaitable;
import com.rorm.durable.InMemoryDurableJobRuntime;
import com.rorm.ml.stream.JobEvent;
import com.rorm.ml.stream.JobEventType;
import dev.restate.client.Client;
import dev.restate.sdk.testing.BindService;
import dev.restate.sdk.testing.RestateClient;
import dev.restate.sdk.testing.RestateTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@RestateTest
@DisplayName("Restate durable job runtime")
class RestateToolCallIntegrationTest {

    private final AwakeableRegistry awakeableRegistry = new AwakeableRegistry();

    @BindService
    JobAwaitWorkflow workflow = new JobAwaitWorkflow(awakeableRegistry);

    @Test
    @DisplayName("submit via runtime, resolve awakeable, get result through Awaitable.get()")
    void submitAndAwait(@RestateClient Client restateClient) throws Exception {
        var runtime = new RestateDurableJobRuntime(restateClient);
        var jobId = UUID.randomUUID();

        // Submit a job that "runs" and then blocks on the awakeable
        Awaitable<JobEvent> awaitable = runtime.submit("TestJob", () -> {
            // In real code this would be: mlService.submit(request) then futureRegistry.await()
            // Here we just return the jobId so we can resolve the awakeable externally
            return null; // the result comes from the awakeable, not from here
        });

        // The workflow is running, waiting for its awakeable to be resolved
        var awakeableId = waitForAwakeable(awaitable.jobId());

        // Simulate Redis event arriving → resolves the awakeable
        var expectedResult = successEvent(awaitable.jobId());
        restateClient.awakeableHandle(awakeableId)
            .resolve(JobEvent.class, expectedResult);

        // Awaitable.get() delegates to runtime.getResult() which attaches to the workflow
        assertThat(runtime.isDone(awaitable.jobId())).isTrue();
        var result = (JobEvent) runtime.getResult(awaitable.jobId());
        assertThat(result.jobId()).isEqualTo(awaitable.jobId());
        assertThat(result.isSuccess()).isTrue();
    }

    private static JobEvent successEvent(UUID jobId) {
        return new JobEvent(
            jobId, JobEventType.JOB_SUCCESS, Instant.now(),
            1.0, "Completed",
            Map.of("accuracy", 0.95),
            null, null, Map.of()
        );
    }

    @Test
    @DisplayName("in-memory runtime: submit and complete via direct future")
    void inMemoryRuntime() throws Exception {
        var runtime = new InMemoryDurableJobRuntime();

        Awaitable<String> awaitable = runtime.submit("TestJob", () -> {
            Thread.sleep(50);
            return "done";
        });

        var result = awaitable.get(5, TimeUnit.SECONDS);
        assertThat(result).isEqualTo("done");
        assertThat(awaitable.isDone()).isTrue();
    }

    private String waitForAwakeable(UUID jobId) {
        for (int i = 0; i < 50; i++) {
            var id = awakeableRegistry.getAndRemove(jobId);
            if (id.isPresent()) {
                return id.get();
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        throw new AssertionError("Awakeable not registered within 5 seconds for job " + jobId);
    }
}
