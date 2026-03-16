package com.rorm.ai.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@DisplayName("Durable chat checkpoint-restore blackbox")
class DurableChatBlackboxTest {

    static { java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC")); }

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:0.8.1-pg18-trixie")
        .withDatabaseName("testdb")
        .withUsername("test")
        .withPassword("test")
        .withEnv("TZ", "UTC")
        .withEnv("PGTZ", "UTC")
        .withCommand("postgres", "-c", "timezone=UTC");

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("chat fails on missing file, resumes in new process after file creation")
    void checkpointRestore() throws Exception {
        var filePath = tempDir.resolve("checkpoint-test.txt");
        var conversationId = "blackbox-" + System.nanoTime();

        var firstRun = runInSeparateProcess(conversationId, filePath);
        assertThat(firstRun).as("First run should fail (file missing)").isNotEqualTo(0);

        Files.writeString(filePath, "test content for checkpoint verification");

        var secondRun = runInSeparateProcess(conversationId, filePath);
        assertThat(secondRun).as("Second run should succeed (file exists)").isEqualTo(0);

        verifyMessages(conversationId);
    }

    private int runInSeparateProcess(String conversationId, Path filePath) throws Exception {
        var isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        var javaBin = isWindows ? "java.exe" : "java";
        var javaCmd = Path.of(System.getProperty("java.home"), "bin", javaBin).toString();

        var argFile = writeArgFile(conversationId, filePath);

        var pb = new ProcessBuilder(javaCmd, "@" + argFile.toAbsolutePath());
        pb.redirectErrorStream(true);

        var process = pb.start();
        var output = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("Process should complete").isTrue();

        System.out.println("[BLACKBOX] Exit: " + process.exitValue());
        if (!output.isBlank()) {
            output.lines().limit(20).forEach(l -> System.out.println("[BLACKBOX] " + l));
        }

        return process.exitValue();
    }

    private Path writeArgFile(String conversationId, Path filePath) throws IOException {
        var classpath = System.getProperty("java.class.path");
        var argFile = tempDir.resolve("args-" + System.nanoTime() + ".txt");
        Files.writeString(argFile, String.join("\n",
            "--enable-preview",
            "-Duser.timezone=UTC",
            "-cp",
            classpath,
            "-Dblackbox.jdbc.url=" + postgres.getJdbcUrl(),
            "-Dblackbox.file.path=" + filePath.toAbsolutePath(),
            "-Dblackbox.conversation.id=" + conversationId,
            DurableChatBlackboxRunner.class.getName()
        ));
        return argFile;
    }

    private void verifyMessages(String conversationId) throws Exception {
        try (var conn = DriverManager.getConnection(postgres.getJdbcUrl(), "test", "test");
             var stmt = conn.prepareStatement(
                 "SELECT type, \"timestamp\" FROM SPRING_AI_CHAT_MEMORY " +
                 "WHERE conversation_id = ? ORDER BY \"timestamp\"")) {
            stmt.setString(1, conversationId);
            var rs = stmt.executeQuery();

            var types = new ArrayList<String>();
            var timestamps = new ArrayList<java.sql.Timestamp>();
            while (rs.next()) {
                types.add(rs.getString("type"));
                timestamps.add(rs.getTimestamp("timestamp"));
            }

            System.out.println("[VERIFY] Messages in DB: " + types.size());
            types.forEach(t -> System.out.println("[VERIFY]   " + t));

            assertThat(types).as(
                "Expected exactly: USER, ASSISTANT(tool_call), TOOL_RESPONSE, ASSISTANT(final). " +
                "No duplicates from resume."
            ).hasSize(4);

            for (int i = 1; i < timestamps.size(); i++) {
                assertThat(timestamps.get(i))
                    .as("Message %d should be after message %d", i, i - 1)
                    .isAfterOrEqualTo(timestamps.get(i - 1));
            }
        }
    }
}
