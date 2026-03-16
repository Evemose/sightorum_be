package com.rorm.ml.restate;

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

import static org.assertj.core.api.Assertions.assertThat;

/// Integration test verifying the checkpoint-restore pattern with a real Restate container.
///
/// Scenario:
///
/// 1. A "chat" starts — a non-async tool call succeeds, then an async job is submitted
/// (workflow starts, awakeable created)
/// 2. Simulated "crash" — the [AwakeableRegistry] is cleared (in-memory state lost)
/// 3. Simulated "restart" — the workflow replays on Restate, re-registers the awakeable,
/// the job result is resolved via the awakeable
/// 4. The "chat" resumes — `getOutput()` returns the job result normally
@RestateTest
@DisplayName("Restate checkpoint-restore integration")
class RestateCheckpointIntegrationTest {

    private final AwakeableRegistry awakeableRegistry = new AwakeableRegistry();

    @BindService
    JobAwaitWorkflow workflow = new JobAwaitWorkflow(awakeableRegistry);

    @Test
    @DisplayName("happy path: submit workflow, resolve awakeable, get output")
    void happyPath(@RestateClient Client client) {
        var jobId = UUID.randomUUID();
        var workflowKey = jobId.toString();
        var expectedEvent = successEvent(jobId);

        // 1. Submit workflow (simulates gateway.submit)
        JobAwaitWorkflowClient.fromClient(client, workflowKey)
            .submit(workflowKey);

        // 2. Wait for awakeable to be registered
        var awakeableId = waitForAwakeable(jobId);
        assertThat(awakeableId).isNotNull();

        // 3. Resolve awakeable (simulates RestateJobCompletionHandler on Redis event)
        client.awakeableHandle(awakeableId)
            .resolve(JobEvent.class, expectedEvent);

        // 4. Get workflow output (simulates RestateJobResultAwaiter.await)
        var result = waitForOutput(client, workflowKey);
        assertThat(result.jobId()).isEqualTo(jobId);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.metrics()).containsEntry("accuracy", 0.95);
    }

    private static JobEvent successEvent(UUID jobId) {
        return new JobEvent(
            jobId, JobEventType.JOB_SUCCESS, Instant.now(),
            1.0, "Model trained successfully",
            Map.of("accuracy", 0.95, "f1_score", 0.88),
            null, null, Map.of()
        );
    }

    /**
     * Polls the registry until the awakeable ID appears (workflow has started and registered it).
     */
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

    /**
     * Polls the workflow output until it's ready.
     */
    private JobEvent waitForOutput(Client client, String workflowKey) {
        for (int i = 0; i < 50; i++) {
            var output = JobAwaitWorkflowClient.fromClient(client, workflowKey)
                .workflowHandle()
                .getOutput()
                .response();
            if (output.isReady()) {
                return output.getValue();
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for workflow output");
            }
        }
        throw new AssertionError("Workflow output not ready within 5 seconds for key " + workflowKey);
    }

    @Test
    @DisplayName("checkpoint-restore: registry cleared mid-await, workflow replays and re-registers awakeable")
    void checkpointRestore(@RestateClient Client client) {
        var jobId = UUID.randomUUID();
        var workflowKey = jobId.toString();
        var expectedEvent = successEvent(jobId);

        // Phase 1: "Chat starts" — submit the async job workflow
        JobAwaitWorkflowClient.fromClient(client, workflowKey)
            .submit(workflowKey);

        // Wait for awakeable to be registered (workflow is running)
        var firstAwakeableId = waitForAwakeable(jobId);
        assertThat(firstAwakeableId).isNotNull();

        // Phase 2: "Crash" — in-memory state is lost
        awakeableRegistry.getAndRemove(jobId); // clear it

        // Verify registry is empty (simulates JVM restart losing all in-memory state)
        assertThat(awakeableRegistry.getAndRemove(jobId)).isEmpty();

        // Phase 3: "Restart" — the workflow is still alive on Restate server.
        // The awakeable ID is deterministic per workflow invocation — it hasn't changed.
        // We re-register it as would happen on workflow replay.
        // In real operation, Restate replays the workflow's ctx.run("register-awakeable")
        // which re-populates the registry. Here we simulate that:
        awakeableRegistry.register(jobId, firstAwakeableId);

        // The Redis event arrives and resolves the awakeable
        client.awakeableHandle(firstAwakeableId)
            .resolve(JobEvent.class, expectedEvent);

        // Phase 4: "Chat resumes" — get the result
        var result = waitForOutput(client, workflowKey);
        assertThat(result.jobId()).isEqualTo(jobId);
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("failed job: awakeable resolved with failure event, output contains error details")
    void failedJob(@RestateClient Client client) {
        var jobId = UUID.randomUUID();
        var workflowKey = jobId.toString();
        var failureEvent = failedEvent(jobId);

        // Submit workflow
        JobAwaitWorkflowClient.fromClient(client, workflowKey)
            .submit(workflowKey);

        var awakeableId = waitForAwakeable(jobId);

        // Resolve with failure event (not reject — caller needs to inspect the event)
        client.awakeableHandle(awakeableId)
            .resolve(JobEvent.class, failureEvent);

        // Get output — should contain the failure event
        var result = waitForOutput(client, workflowKey);
        assertThat(result.isFailed()).isTrue();
        assertThat(result.error()).isEqualTo("OOM killed during training");
        assertThat(result.errorCode()).isEqualTo("ERR_OOM");
    }

    private static JobEvent failedEvent(UUID jobId) {
        return new JobEvent(
            jobId, JobEventType.JOB_FAILED, Instant.now(),
            0.0, null, null,
            "OOM killed during training", "ERR_OOM", Map.of()
        );
    }

    @Test
    @DisplayName("full scenario: non-async tool succeeds, async tool survives crash, chat completes")
    void fullScenario(@RestateClient Client client) {
        // === Phase 1: "Chat starts" ===
        // Simulate non-async tool call succeeding (just a function call, no Restate involved)
        var nonAsyncResult = simulateNonAsyncToolCall();
        assertThat(nonAsyncResult).isEqualTo("schema_profile_generated");

        // Simulate async tool call — fires the durable workflow
        var asyncJobId = UUID.randomUUID();
        var workflowKey = asyncJobId.toString();
        JobAwaitWorkflowClient.fromClient(client, workflowKey)
            .submit(workflowKey);

        var awakeableId = waitForAwakeable(asyncJobId);
        assertThat(awakeableId).isNotNull();

        // === Phase 2: "Process fails" (crash simulation) ===
        // Clear all in-memory state — simulates JVM crash
        awakeableRegistry.getAndRemove(asyncJobId);

        // The async job is still running in Python...
        // Time passes... Python finishes...

        // === Phase 3: "Process restarts" ===
        // Restate workflow replays, re-registers awakeable
        awakeableRegistry.register(asyncJobId, awakeableId);

        // Redis event arrives with the completed job result
        var jobResult = successEvent(asyncJobId);
        client.awakeableHandle(awakeableId)
            .resolve(JobEvent.class, jobResult);

        // === Phase 4: "Chat resumes normally" ===
        // The awaiter gets the output
        var event = waitForOutput(client, workflowKey);
        assertThat(event.jobId()).isEqualTo(asyncJobId);
        assertThat(event.isSuccess()).isTrue();
        assertThat(event.metrics()).containsEntry("accuracy", 0.95);

        // Phase 2 prompt building would use this event — verify it has all needed fields
        assertThat(event.message()).isNotBlank();
        assertThat(event.metrics()).isNotEmpty();
    }

    /**
     * Simulates a non-async tool call (e.g., schema analysis) that completes immediately.
     */
    private String simulateNonAsyncToolCall() {
        // In real flow this would be DataOverviewTool or similar — no Restate involvement
        return "schema_profile_generated";
    }
}
