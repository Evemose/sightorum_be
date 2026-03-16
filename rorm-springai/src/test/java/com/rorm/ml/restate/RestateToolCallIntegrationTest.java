package com.rorm.ml.restate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.ml.AsyncJobGateway;
import com.rorm.ml.MlTrainingService;
import com.rorm.ml.dto.AsyncJobRequest;
import com.rorm.ml.dto.DatasourceConfig;
import com.rorm.ml.dto.TrainingJobRequest;
import com.rorm.ml.dto.model.train.RandomForestClassifierConfig;
import com.rorm.ml.peristence.MLJobMetadataStore;
import com.rorm.ml.stream.JobEvent;
import com.rorm.ml.stream.JobEventType;
import dev.restate.client.Client;
import dev.restate.sdk.testing.BindService;
import dev.restate.sdk.testing.RestateClient;
import dev.restate.sdk.testing.RestateTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test exercising the full tool-call flow with a real Restate container.
 *
 * <p>Scenario:
 * <ol>
 *   <li>A "chat" starts — a non-async tool succeeds, then an async tool fires a durable job</li>
 *   <li>Simulated "crash" — in-memory awakeable registry is cleared</li>
 *   <li>"Restart" — Restate workflow replays, awakeable re-registered, job result resolved</li>
 *   <li>The awaiter gets the result — chat completes normally</li>
 * </ol>
 *
 * <p>Uses real Spring AI tool callbacks (invoked via {@code MethodToolCallbackProvider}),
 * a real {@link AsyncJobGateway} backed by Restate, and a real {@link com.rorm.ml.JobResultAwaiter}.
 * Only the Python ML service and the database are stubbed.
 */
@RestateTest
@DisplayName("Restate tool-call integration")
class RestateToolCallIntegrationTest {

    private final AwakeableRegistry awakeableRegistry = new AwakeableRegistry();
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @BindService
    JobAwaitWorkflow workflow = new JobAwaitWorkflow(awakeableRegistry);

    // ---- test tool implementations ----

    @Test
    @DisplayName("full flow: sync tool succeeds, async tool fires durable job, crash simulated, result recovered")
    void fullToolCallFlow(@RestateClient Client restateClient) throws Exception {
        // === Setup: wire the gateway and awaiter with the real Restate client ===
        var stubMlService = new StubMlTrainingService();
        var metadataStore = new StubMetadataStore();
        var gateway = new RestateAsyncJobGateway(metadataStore, stubMlService, restateClient);
        var awaiter = new RestateJobResultAwaiter(restateClient);
        var completionHandler = new RestateJobCompletionHandler(awakeableRegistry, restateClient);

        // === Phase 1: "Chat starts" ===
        // Build tool callbacks from real tool classes (same mechanism Spring AI uses)
        var syncTool = new SyncAnalysisTool();
        var asyncTool = new AsyncTrainingTool(gateway);
        var launchedJobs = new CopyOnWriteArrayList<UUID>();

        var toolContext = new ToolContext(Map.of(
            "launchedJobs", launchedJobs
        ));

        // Resolve tool callbacks via Spring AI's mechanism
        var syncCallbacks = MethodToolCallbackProvider.builder().toolObjects(syncTool).build().getToolCallbacks();
        var asyncCallbacks = MethodToolCallbackProvider.builder().toolObjects(asyncTool).build().getToolCallbacks();

        // Step 1: Non-async tool call succeeds
        var syncCallback = syncCallbacks[0];
        var syncResult = syncCallback.call(
            objectMapper.writeValueAsString(Map.of("tableName", "customers")),
            toolContext
        );
        assertThat(syncResult).contains("Schema for customers");
        assertThat(launchedJobs).isEmpty(); // sync tool doesn't launch jobs

        // Step 2: Async tool call fires a durable job
        var asyncCallback = asyncCallbacks[0];
        var asyncResult = asyncCallback.call(
            objectMapper.writeValueAsString(Map.of("reason", "Predict churn", "modelType", "random_forest")),
            toolContext
        );
        assertThat(asyncResult).contains("Training launched:");
        assertThat(launchedJobs).hasSize(1);

        var jobId = launchedJobs.getFirst();

        // === Phase 2: "Chat checks launched jobs, enters await" ===
        // In real code, ExecutorSwarmAgent does this after the chat completes.
        // We do it on a background thread so we can simulate crash + resolve in parallel.
        var awaitFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return awaiter.await(jobId, 30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        });

        // === Phase 3: Simulate "crash" — clear in-memory state ===
        var awakeableId = waitForAwakeable(jobId);
        assertThat(awakeableId).isNotNull();

        awakeableRegistry.getAndRemove(jobId); // "crash" — registry lost
        assertThat(awakeableRegistry.getAndRemove(jobId)).isEmpty();

        // === Phase 4: "Restart" — re-register awakeable (Restate replay would do this) ===
        awakeableRegistry.register(jobId, awakeableId);

        // Job completes in Python, Redis event arrives, completion handler resolves awakeable
        var jobResult = new JobEvent(
            jobId, JobEventType.JOB_SUCCESS, Instant.now(),
            1.0, "Training completed",
            Map.of("accuracy", 0.93, "f1_score", 0.87),
            null, null, Map.of()
        );
        var jobInfo = new com.rorm.ml.peristence.MLJobInfo(jobId, "Predict churn", null, null, null);
        completionHandler.onJobSuccess(jobInfo, jobResult);

        // === Phase 5: "Chat resumes" — await completes with job result ===
        var result = awaitFuture.get(10, TimeUnit.SECONDS);
        assertThat(result).isNotNull();
        assertThat(result.jobId()).isEqualTo(jobId);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.metrics()).containsEntry("accuracy", 0.93);

        // Phase-2 prompt can now be built with this result
        awaiter.remove(jobId);
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

    // ---- the actual integration test ----

    /**
     * A non-async tool that returns immediately. Simulates schema analysis, data overview, etc.
     */
    static class SyncAnalysisTool {
        @Tool(description = "Analyze schema structure")
        public String analyzeSchema(@ToolParam(description = "table name") String tableName) {
            return "Schema for " + tableName + ": 5 columns, 1000 rows";
        }
    }

    // ---- stubs ----

    /**
     * An async tool that fires a durable job via the gateway.
     * This is the real integration point — it calls {@code jobGateway.submit()}.
     */
    static class AsyncTrainingTool {
        private final AsyncJobGateway jobGateway;

        AsyncTrainingTool(AsyncJobGateway jobGateway) {
            this.jobGateway = jobGateway;
        }

        @Tool(description = "Launch ML training job")
        @SuppressWarnings("unchecked")
        public String launchTraining(
            @ToolParam(description = "reason") String reason,
            @ToolParam(description = "model type") String modelType,
            ToolContext toolContext
        ) {
            var request = TrainingJobRequest.builder()
                .reason(reason)
                .datasource(new DatasourceConfig("SELECT 1", Map.of()))
                .targetColumn("target")
                .featureColumns(List.of("feature1"))
                .modelConfig(RandomForestClassifierConfig.builder().build())
                .build();
            var launchedJobs = (List<UUID>) toolContext.getContext().get("launchedJobs");
            var jobId = jobGateway.submit(request, "test_schema");
            launchedJobs.add(jobId);
            return "Training launched: " + jobId;
        }
    }

    /**
     * Stub ML service that returns a random UUID without hitting Python.
     */
    static class StubMlTrainingService extends MlTrainingService {
        StubMlTrainingService() {
            super(null);
        }

        @Override
        public UUID submit(AsyncJobRequest request) {
            return UUID.randomUUID();
        }
    }

    // ---- infrastructure ----

    /**
     * Stub metadata store that records persisted jobs without a database.
     */
    static class StubMetadataStore extends MLJobMetadataStore {
        private final Map<UUID, com.rorm.ml.peristence.MLJobInfo> store = new ConcurrentHashMap<>();

        StubMetadataStore() {
            super(null, null);
        }

        @Override
        public void persistJob(UUID jobId, AsyncJobRequest request, String schema) {
            store.put(jobId, new com.rorm.ml.peristence.MLJobInfo(
                jobId, request.reason(), request.furtherInstructions()));
        }

        @Override
        public Optional<com.rorm.ml.peristence.MLJobInfo> findByJobId(UUID jobId) {
            return Optional.ofNullable(store.get(jobId));
        }
    }
}
