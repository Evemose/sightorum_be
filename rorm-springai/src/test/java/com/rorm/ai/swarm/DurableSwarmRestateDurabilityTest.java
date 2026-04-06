package com.rorm.ai.swarm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.wiremock.integrations.testcontainers.WireMockContainer;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Restate durability test for {@link DurableSwarm}. Verifies that a swarm
 * pipeline survives process crashes by replaying from Restate's journal.
 * <p>
 * Pattern (same as {@code RestateCheckpointIntegrationTest}):
 * <ol>
 *   <li>Start Restate + WireMock containers</li>
 *   <li>Launch App 1 — invokes swarm via Restate ingress</li>
 *   <li>Kill App 1 mid-execution (after scout phase completes)</li>
 *   <li>Launch App 2 on the same SDK port — Restate retries, replaying
 *       journal entries for completed LLM calls without re-executing them</li>
 *   <li>Assert: pipeline completes, LLM call count on App 2 is less than
 *       full pipeline (some calls replayed from journal)</li>
 * </ol>
 */
@DisplayName("DurableSwarm Restate durability across shutdowns")
@Testcontainers
class DurableSwarmRestateDurabilityTest {

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> RESTATE = new GenericContainer<>("docker.io/restatedev/restate:1.6")
        .withExposedPorts(8080, 9070)
        .waitingFor(Wait.forListeningPort());
    @Container
    static final WireMockContainer WIREMOCK = new WireMockContainer("wiremock/wiremock:3.12.1");
    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
        .withExposedPorts(6379)
        .waitingFor(Wait.forListeningPort());
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
        DockerImageName.parse("pgvector/pgvector:0.8.1-pg18-trixie")
            .asCompatibleSubstituteFor("postgres")
    );
    private static final int SDK_PORT = 19082;
    private static final String SESSION_ID = "swarm-durability-test";
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    /**
     * Total LLM calls in the full pipeline:
     * <pre>
     * scout(1) + domain(1)                                          =  2
     * anchor1: gen(1) + sceptic(1) + rebuttal(1) + 2×(comp+spec+FP) =  9
     * anchor2: gen(1) + sceptic(1) + rebuttal(1) + 2×(comp+spec+FP) =  9
     *                                                         total = 20
     * </pre>
     */
    private static final int TOTAL_LLM_CALLS = 20;

    static {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    private final CopyOnWriteArrayList<Process> managedProcesses = new CopyOnWriteArrayList<>();
    @TempDir
    Path tempDir;

    @AfterEach
    void killSubprocesses() {
        managedProcesses.forEach(Process::destroyForcibly);
    }

    @Test
    @DisplayName("pipeline survives process kill and completes via Restate journal replay")
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void pipelineSurvivesProcessKill() throws Exception {
        org.testcontainers.Testcontainers.exposeHostPorts(SDK_PORT);
        initDatabase();

        WireMock.configureFor(
            WIREMOCK.getHost(), WIREMOCK.getFirstMappedPort());
        SwarmTestFixtures.registerAllStubs();
        // Override compiler stub with delay so the kill happens during active LLM processing.
        // Exclude spec extraction requests which also contain "pipeline compiler" in chat history.
        WireMock.stubFor(
            WireMock.post(
                    WireMock.urlEqualTo("/v1/messages"))
                .atPriority(0)
                .withRequestBody(WireMock.containing("pipeline compiler"))
                .withRequestBody(WireMock.notContaining("Extract a PipelineSpecRequest"))
                .willReturn(SwarmTestFixtures.anthropicResponse(SwarmTestFixtures.COMPILER_RESPONSE)
                    .withFixedDelay(3000)));

        var signalDir = tempDir.resolve("signals");
        Files.createDirectories(signalDir);

        try (var httpClient = HttpClient.newHttpClient()) {
            var ingressUrl = "http://localhost:" + RESTATE.getMappedPort(8080);
            var adminUrl = "http://localhost:" + RESTATE.getMappedPort(9070);

            // ── App 1: start, invoke, kill ────────────────────────────
            var app1Output = new StringBuilder();
            var app1 = startApp(signalDir, adminUrl, SDK_PORT, app1Output);
            awaitReady(app1, app1Output);

            var resultFuture = CompletableFuture.supplyAsync(() ->
                invokeSwarmHandler(httpClient, ingressUrl));

            await("LLM calls signal file from App 1")
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(500))
                .failFast("Invocation failed in App 1", () -> outputContains(app1Output, "Invocation failed"))
                .until(() -> Files.exists(signalDir.resolve("llm_calls.txt")));

            // Let a few calls journal but kill before compiler finishes (3s delay on compiler stub)
            Thread.sleep(2000);

            var callsBeforeKill = readCallCount(signalDir);
            System.out.println("[TEST] LLM calls before kill: " + callsBeforeKill);

            app1.destroyForcibly().waitFor();
            System.out.println("[TEST] App 1 killed");

            Files.deleteIfExists(signalDir.resolve("llm_calls.txt"));

            // ── App 2: same port, Restate retries via journal replay ──
            var app2Output = new StringBuilder();
            var app2 = startApp(signalDir, adminUrl, SDK_PORT, app2Output);
            awaitReady(app2, app2Output);

            // Resume the paused invocation so Restate dispatches to App 2
            resumePausedInvocation(httpClient, adminUrl);

            // Periodically publish ML job completion events so awakeables resolve.
            // The pipeline has multiple hypotheses, each creating a new awakeable
            // for the same WireMock job ID. We keep publishing until the pipeline finishes.
            var completionPublisher = CompletableFuture.runAsync(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        publishJobCompletion(SwarmTestFixtures.PIPELINE_JOB_ID);
                        Thread.sleep(3000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            });

            // Restate wraps the handler's String return value in JSON quotes,
            // so the result is a JSON-encoded string containing our JSON.
            var rawResult = resultFuture.get(120, TimeUnit.SECONDS);
            var resultJson = MAPPER.readValue(rawResult, String.class);
            completionPublisher.cancel(true);

            await("LLM calls signal file from App 2")
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(500))
                .failFast("Invocation failed in App 2", () -> outputContains(app2Output, "Invocation failed"))
                .until(() -> Files.exists(signalDir.resolve("llm_calls.txt")));

            var callsOnApp2 = readCallCount(signalDir);
            System.out.println("[TEST] LLM calls before kill: " + callsBeforeKill
                               + ", on App 2: " + callsOnApp2
                               + ", total expected: " + TOTAL_LLM_CALLS);

            // ── Replay contract: call conservation ──────────────────
            // The counter increments only after a call completes (not on start),
            // so in-flight calls at kill time are not counted on App 1.
            // App 2 replays completed calls from journal, re-executes the rest.
            // Total completed calls across both apps must equal the full pipeline.
            assertThat(callsBeforeKill + callsOnApp2)
                .as("Call conservation: every LLM call completes exactly once across "
                    + "both apps (%d on App1 + %d on App2)",
                    callsBeforeKill, callsOnApp2)
                .isEqualTo(TOTAL_LLM_CALLS);

            assertThat(callsOnApp2)
                .as("App 2 must make fewer calls than a full run — "
                    + "proving journal replay skipped already-completed steps")
                .isLessThan(TOTAL_LLM_CALLS);

            // ── Result structural correctness after replay ──────────
            // The restored pipeline must produce the same result structure
            // as if it ran uninterrupted on a single process.
            var result = MAPPER.readValue(resultJson, SwarmResult.class);

            assertThat(result.scoutOutput()).isEqualTo(SwarmTestFixtures.SCOUT_RESPONSE);
            assertThat(result.domainResearch()).isEqualTo(SwarmTestFixtures.DOMAIN_RESPONSE);

            assertThat(result.anchorResults())
                .as("Pipeline should complete both anchors after replay")
                .hasSize(2)
                .extracting(SwarmResult.AnchorResult::anchor)
                .containsExactly(SwarmTestFixtures.ANCHOR_CONTAINERS, SwarmTestFixtures.ANCHOR_VEHICLES);

            // Anchor 1 (containers): 2 hypotheses
            var anchor1 = result.anchorResults().get(0);
            assertThat(anchor1.generatorOutput()).isEqualTo(SwarmTestFixtures.GENERATOR_RESPONSE);
            assertThat(anchor1.revisedOutput()).isEqualTo(SwarmTestFixtures.REBUTTAL_RESPONSE);
            assertThat(anchor1.hypothesisResults())
                .hasSize(2)
                .extracting(SwarmResult.HypothesisResult::hypothesisId)
                .containsExactly("H1", "H2");
            assertThat(anchor1.hypothesisResults())
                .as("Every hypothesis pipeline must complete with JOB_SUCCESS after replay")
                .allSatisfy(h -> {
                    assertThat(h.compilerOutput()).isEqualTo(SwarmTestFixtures.COMPILER_RESPONSE);
                    assertThat(h.pipelineResult().eventType())
                        .isEqualTo(com.rorm.ml.stream.JobEventType.JOB_SUCCESS);
                    assertThat(h.pipelineResult().message())
                        .isEqualTo("Pipeline completed successfully");
                    assertThat(h.diagnosis()).isEqualTo(SwarmTestFixtures.FP_RESPONSE);
                });

            // Anchor 2 (vehicles): 2 hypotheses (same rebuttal as anchor 1)
            var anchor2 = result.anchorResults().get(1);
            assertThat(anchor2.generatorOutput()).isEqualTo(SwarmTestFixtures.GENERATOR_RESPONSE_VEHICLES);
            assertThat(anchor2.hypothesisResults())
                .hasSize(2)
                .extracting(SwarmResult.HypothesisResult::hypothesisId)
                .containsExactly("H1", "H2");
            assertThat(anchor2.hypothesisResults())
                .allSatisfy(h -> {
                    assertThat(h.pipelineResult().eventType())
                        .isEqualTo(com.rorm.ml.stream.JobEventType.JOB_SUCCESS);
                    assertThat(h.diagnosis()).isEqualTo(SwarmTestFixtures.FP_RESPONSE);
                });
        }
    }

    private static void initDatabase() throws Exception {
        try (var conn = java.sql.DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var stmt = conn.createStatement()) {
            stmt.execute("""
                create table if not exists chat_memory (
                    id              bigint generated always as identity primary key,
                    conversation_id varchar(256)             not null,
                    message_type    varchar(20)              not null,
                    payload         JSONB                    not null,
                    created_at      timestamp with time zone not null default now()
                )""");
            stmt.execute("""
                create index if not exists idx_chat_memory_conversation
                    on chat_memory (conversation_id, created_at)""");
        }
    }

    private Process startApp(Path signalDir, String adminUrl, int sdkPort, StringBuilder output) throws Exception {
        var classpath = System.getProperty("java.class.path");
        var redisHost = REDIS.getHost();
        var redisPort = REDIS.getMappedPort(6379);
        var argfile = tempDir.resolve("argfile_" + System.nanoTime() + ".txt");
        Files.writeString(argfile,
            "--enable-preview\n" +
            "-Duser.timezone=UTC\n" +
            "-Dblackbox.signal.dir=" + signalDir.toAbsolutePath() + "\n" +
            "-cp\n" + classpath + "\n" +
            DurableSwarmTestApplication.class.getName() + "\n" +
            // Server
            "--server.port=0\n" +
            "--spring.main.banner-mode=off\n" +
            "--spring.main.allow-bean-definition-overriding=true\n" +
            "--spring.docker.compose.enabled=false\n" +
            // Redis (url takes precedence over host/port; application-ml.yml sets url)
            "--spring.data.redis.url=redis://" + redisHost + ":" + redisPort + "\n" +
            // DataSource
            "--spring.datasource.url=" + POSTGRES.getJdbcUrl() + "\n" +
            "--spring.datasource.username=" + POSTGRES.getUsername() + "\n" +
            "--spring.datasource.password=" + POSTGRES.getPassword() + "\n" +
            "--spring.jpa.hibernate.ddl-auto=update\n" +
            "--spring.autoconfigure.exclude=org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration\n" +
            // Anthropic → WireMock
            "--anthropic.api-key=test-key\n" +
            "--anthropic.base-url=" + WIREMOCK.getBaseUrl() + "\n" +
            "--wiremock.base-url=" + WIREMOCK.getBaseUrl() + "\n" +
            // ML service → WireMock
            "--rorm.ml.service-base-url=" + WIREMOCK.getBaseUrl() + "\n" +
            "--rorm.ml.event-stream-name=ml_training:training_results\n" +
            "--rorm.ml.consumer-group=test_consumers\n" +
            // Restate
            "--rorm.ml.durable-execution=true\n" +
            "--restate.sdk.http.port=" + sdkPort + "\n" +
            "--rorm.ml.restate-admin-url=" + adminUrl + "\n" +
            "--rorm.ml.restate-endpoint-url=http://host.docker.internal:" + sdkPort + "\n" +
            "--restate.client.base-uri=http://localhost:" + RESTATE.getMappedPort(8080) + "\n" +
            // OpenAI (unused but needs key to not fail autoconfiguration)
            "--spring.ai.openai.api-key=fake\n" +
            // Logging — include com.rorm.ml.restate so registration message is visible
            "--logging.level.root=WARN\n" +
            "--logging.level.com.rorm.ai.swarm=INFO\n" +
            "--logging.level.com.rorm.ml.restate=INFO\n" +
            "--logging.level.dev.restate=DEBUG\n" +
            "--logging.level.org.springframework.boot=INFO\n"
        );
        var process = new ProcessBuilder(
            ProcessHandle.current().info().command().orElse("java"),
            "@" + argfile.toAbsolutePath()
        ).redirectErrorStream(true).start();
        managedProcesses.add(process);

        // Drain stdout in background so the process never blocks on a full buffer
        Thread.ofVirtual().name("drain-" + process.pid()).start(() -> {
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println("[APP] " + line);
                    synchronized (output) {
                        output.append(line).append('\n');
                    }
                }
            } catch (Exception ignored) {
            }
        });

        return process;
    }

    private void awaitReady(Process process, StringBuilder output) {
        await("App subprocess to become ready")
            .atMost(Duration.ofSeconds(60))
            .pollInterval(Duration.ofMillis(500))
            .untilAsserted(() -> {
                assertThat(process.isAlive()).as("App process should be alive").isTrue();
                synchronized (output) {
                    var text = output.toString();
                    assertThat(text)
                        .as("Should not have port bind failure")
                        .doesNotContain("Address already in use");
                    assertThat(text)
                        .as("App should register with Restate")
                        .contains("Restate deployment registered");
                }
            });
    }

    private String invokeSwarmHandler(HttpClient client, String ingressUrl) {
        try {
            var response = client.send(
                HttpRequest.newBuilder()
                    .uri(URI.create(ingressUrl + "/SwarmHandler/" + SESSION_ID + "/run"))
                    .POST(HttpRequest.BodyPublishers.ofString("\"go\""))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(300))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (Exception e) {
            throw new RuntimeException("Restate invocation failed", e);
        }
    }

    private static boolean outputContains(StringBuilder output, String marker) {
        synchronized (output) {
            return output.toString().contains(marker);
        }
    }

    private int readCallCount(Path signalDir) throws Exception {
        var file = signalDir.resolve("llm_calls.txt");
        if (!Files.exists(file)) {
            return -1;
        }
        return Integer.parseInt(Files.readString(file).trim());
    }

    private void resumePausedInvocation(HttpClient client, String adminUrl) throws Exception {
        // Query ALL statuses to diagnose, then resume if paused
        var debugQuery = "SELECT id, status FROM sys_invocation WHERE target_service_name = 'SwarmHandler' LIMIT 5";
        var debugBody = MAPPER.writeValueAsString(Map.of("query", debugQuery));
        var debugResp = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create(adminUrl + "/query"))
                .POST(HttpRequest.BodyPublishers.ofString(debugBody))
                .header("Content-Type", "application/json")
                .header("Accept-Encoding", "identity")
                .build(),
            HttpResponse.BodyHandlers.ofString());
        System.out.println("[TEST] SwarmHandler invocations: " + debugResp.body());

        var query = "SELECT id FROM sys_invocation WHERE target_service_name = 'SwarmHandler' AND (status = 'suspended' OR status = 'paused') LIMIT 1";
        var queryBody = MAPPER.writeValueAsString(Map.of("query", query));

        var invocationId = new String[1];
        await("suspended/paused Restate invocation to appear")
            .atMost(Duration.ofSeconds(30))
            .pollInterval(Duration.ofSeconds(1))
            .ignoreExceptions()
            .until(() -> {
                var resp = client.send(
                    HttpRequest.newBuilder()
                        .uri(URI.create(adminUrl + "/query"))
                        .POST(HttpRequest.BodyPublishers.ofString(queryBody))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .header("Accept-Encoding", "identity")
                        .build(),
                    HttpResponse.BodyHandlers.ofString());
                if (resp.body().contains("\"id\"")) {
                    invocationId[0] = MAPPER.readTree(resp.body()).path("rows").get(0).path("id").asText();
                    return true;
                }
                return false;
            });

        System.out.println("[TEST] Resuming paused invocation: " + invocationId[0]);
        client.send(
            HttpRequest.newBuilder()
                .uri(URI.create(adminUrl + "/invocations/" + invocationId[0] + "/resume"))
                .method("PATCH", HttpRequest.BodyPublishers.noBody())
                .header("Content-Type", "application/json")
                .build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private static void publishJobCompletion(java.util.UUID jobId) {
        var redisHost = REDIS.getHost();
        var redisPort = REDIS.getMappedPort(6379);
        var redisUri = io.lettuce.core.RedisURI.builder()
            .withHost(redisHost).withPort(redisPort).build();
        try (var client = io.lettuce.core.RedisClient.create(redisUri);
             var conn = client.connect()) {
            var payload = """
                {"job_id":"%s","event_type":"job.success","timestamp":"%s",\
                "progress":1.0,"message":"Pipeline completed successfully",\
                "metadata":{},"metrics":{}}""".formatted(jobId, java.time.Instant.now());
            conn.sync().xadd("ml_training:training_results",
                java.util.Map.of("payload", payload));
            System.out.println("[TEST] Published job completion for " + jobId);
        }
    }
}
