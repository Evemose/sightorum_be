package com.rorm.ai.anthropic.restate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TimeZone;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class RestateCheckpointIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> RESTATE = new GenericContainer<>("docker.io/restatedev/restate:1.3")
        .withExposedPorts(8080, 9070)
        .waitingFor(Wait.forListeningPort());
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");
    private static final int SDK_PORT = 9081;
    private static final String SESSION_ID = "test-session-1";

    static {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    @TempDir
    Path tempDir;

    @SuppressWarnings("BusyWait")
    @Test
    void checkpointRestoreViaRestate() throws Exception {
        org.testcontainers.Testcontainers.exposeHostPorts(SDK_PORT);

        var filePath = tempDir.resolve("signal.txt");
        var journalDir = tempDir.resolve("journal");
        Files.createDirectories(journalDir);

        try (var httpClient = HttpClient.newHttpClient()) {

            var ingressPort = RESTATE.getMappedPort(8080);
            var adminPort = RESTATE.getMappedPort(9070);
            var ingressUrl = "http://localhost:" + ingressPort;
            var adminUrl = "http://localhost:" + adminPort;

            var check = httpClient.send(
                HttpRequest.newBuilder().uri(URI.create(adminUrl + "/deployments")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            System.out.println("Restate admin check: " + check.statusCode() + " at " + adminUrl);

            // Run 1: start app, invoke handler, tool fails (file missing), app crashes
            var app1 = startApp(filePath, journalDir, adminUrl);
            waitForReady(app1);

            var resultFuture = CompletableFuture.supplyAsync(() ->
                invokeHandler(httpClient, ingressUrl, SESSION_ID, "Check the file please"));

            // Wait for the handler to execute and fail (llm_calls.txt written in finally block)
            var deadline = System.currentTimeMillis() + 15_000;
            while (!Files.exists(journalDir.resolve("llm_calls.txt"))
                   && System.currentTimeMillis() < deadline) {
                Thread.sleep(500);
            }
            app1.destroyForcibly().waitFor();

            // Write call count before verifying
            assertThat(readCallCount(journalDir))
                .as("run1 should have made 1 LLM call before crashing")
                .isEqualTo(1);

            // Create signal file so tool succeeds on retry
            Files.writeString(filePath, "exists");

            // Run 2: start app on same SDK port, Restate retries with journal replay
            var app2 = startApp(filePath, journalDir, adminUrl);
            waitForReady(app2);

            var result = resultFuture.get(60, TimeUnit.SECONDS);
            app2.destroyForcibly().waitFor();

            assertThat(result).contains("File exists");
            assertThat(readCallCount(journalDir))
                .as("run2 should make only 1 LLM call (llm-0 replayed from Restate journal)")
                .isEqualTo(1);
        }
    }

    private Process startApp(Path filePath, Path journalDir, String adminUrl) throws Exception {
        var classpath = System.getProperty("java.class.path");
        var argfile = tempDir.resolve("argfile_" + System.nanoTime() + ".txt");
        Files.writeString(argfile,
            "--enable-preview\n" +
            "-Duser.timezone=UTC\n" +
            "-Dblackbox.file.path=" + filePath.toAbsolutePath() + "\n" +
            "-Dblackbox.journal.dir=" + journalDir.toAbsolutePath() + "\n" +
            "-cp\n" + classpath + "\n" +
            RestateCheckpointTestApp.class.getName() + "\n" +
            "--server.port=0\n" +
            "--restate.sdk.http.port=" + SDK_PORT + "\n" +
            "--restate.admin.url=" + adminUrl + "\n" +
            "--restate.client.base-uri=http://localhost:" + RESTATE.getMappedPort(8080) + "\n" +
            "--spring.main.banner-mode=off\n" +
            "--spring.docker.compose.enabled=false\n" +
            "--spring.ai.openai.api-key=fake\n" +
            "--spring.datasource.url=" + POSTGRES.getJdbcUrl() + "\n" +
            "--spring.datasource.username=" + POSTGRES.getUsername() + "\n" +
            "--spring.datasource.password=" + POSTGRES.getPassword() + "\n" +
            "--logging.level.root=WARN\n" +
            "--logging.level.dev.restate=DEBUG\n" +
            "--logging.level.org.springframework.boot=INFO\n"
        );
        return new ProcessBuilder(
            ProcessHandle.current().info().command().orElse("java"),
            "@" + argfile.toAbsolutePath()
        ).redirectErrorStream(true).start();
    }

    private void waitForReady(Process process) throws Exception {
        var reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        var deadline = System.currentTimeMillis() + 30_000;
        var output = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            System.out.println("[APP] " + line);
            output.append(line).append('\n');
            if (line.contains("READY")) {
                return;
            }
            if (System.currentTimeMillis() > deadline) {
                break;
            }
        }
        if (!process.isAlive()) {
            throw new AssertionError("App exited before READY, exit=" + process.exitValue()
                                     + "\n" + output.substring(Math.max(0, output.length() - 2000)));
        }
        throw new AssertionError("App did not become ready within 30s\n"
                                 + output.substring(Math.max(0, output.length() - 2000)));
    }

    private String invokeHandler(HttpClient client, String ingressUrl, String sessionId, String message) {
        try {
            var response = client.send(
                HttpRequest.newBuilder()
                    .uri(URI.create(ingressUrl + "/TestChatHandler/" + sessionId + "/chat"))
                    .POST(HttpRequest.BodyPublishers.ofString("\"" + message + "\""))
                    .header("Content-Type", "application/json")
                    .timeout(java.time.Duration.ofSeconds(120))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (Exception e) {
            throw new RuntimeException("Restate invocation failed", e);
        }
    }

    private int readCallCount(Path journalDir) throws Exception {
        var file = journalDir.resolve("llm_calls.txt");
        if (!Files.exists(file)) {
            return -1;
        }
        return Integer.parseInt(Files.readString(file).trim());
    }
}
