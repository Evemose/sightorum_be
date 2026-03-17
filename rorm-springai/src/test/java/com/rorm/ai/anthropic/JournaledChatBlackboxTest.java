package com.rorm.ai.anthropic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

class JournaledChatBlackboxTest {

    static {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    @TempDir
    Path tempDir;

    @Test
    void checkpointRestoreAcrossJvmRestarts() throws Exception {
        var filePath = tempDir.resolve("signal.txt");
        var journalDir = tempDir.resolve("journal");
        Files.createDirectories(journalDir);

        var run1 = launchRunner(filePath, journalDir);
        assertThat(run1.exitCode).as("run1 should fail (file missing):\n%s", run1.stdout).isNotEqualTo(0);
        assertThat(readCallCount(journalDir))
            .as("run1 should make exactly 1 LLM call (tool crashed before llm-1)")
            .isEqualTo(1);

        long entriesAfterCrash;
        try (var files = Files.list(journalDir)) {
            entriesAfterCrash = files.filter(p -> p.toString().endsWith(".json")).count();
        }
        assertThat(entriesAfterCrash)
            .as("journal should have partial entries (llm-0 only)")
            .isEqualTo(1);

        Files.writeString(filePath, "exists");

        var run2 = launchRunner(filePath, journalDir);
        assertThat(run2.exitCode).as("run2 should succeed:\n%s", run2.stdout).isEqualTo(0);
        assertThat(run2.stdout).contains("SUCCESS");
        assertThat(readCallCount(journalDir))
            .as("run2 should make exactly 1 LLM call (llm-0 replayed from journal, only llm-1 live)")
            .isEqualTo(1);

        long entriesAfterRestore;
        try (var files = Files.list(journalDir)) {
            entriesAfterRestore = files.filter(p -> p.toString().endsWith(".json")).count();
        }
        assertThat(entriesAfterRestore)
            .as("journal should have all entries (llm-0 replayed, tool, llm-1)")
            .isEqualTo(3);
    }

    private ProcessResult launchRunner(Path filePath, Path journalDir) throws Exception {
        var classpath = System.getProperty("java.class.path");
        var argfile = tempDir.resolve("argfile_" + System.nanoTime() + ".txt");
        Files.writeString(argfile,
            "--enable-preview\n" +
            "-Duser.timezone=UTC\n" +
            "-Dblackbox.file.path=" + filePath.toAbsolutePath() + "\n" +
            "-Dblackbox.journal.dir=" + journalDir.toAbsolutePath() + "\n" +
            "-cp\n" + classpath + "\n" +
            JournaledChatBlackboxRunner.class.getName() + "\n"
        );

        var process = new ProcessBuilder(
            ProcessHandle.current().info().command().orElse("java"),
            "@" + argfile.toAbsolutePath()
        ).redirectErrorStream(true).start();

        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        return new ProcessResult(exitCode, output, output);
    }

    private int readCallCount(Path journalDir) throws IOException {
        return Integer.parseInt(Files.readString(journalDir.resolve("llm_calls.txt")).trim());
    }

    @Test
    void multiRoundCheckpointRestore() throws Exception {
        var filePathA = tempDir.resolve("signal_a.txt");
        var filePathB = tempDir.resolve("signal_b.txt");
        var journalDir = tempDir.resolve("journal_multi");
        Files.createDirectories(journalDir);

        Files.writeString(filePathA, "exists");

        var run1 = launchMultiRoundRunner(filePathA, filePathB, journalDir);
        assertThat(run1.exitCode).as("run1 should fail (fileB missing):\n%s", run1.stdout).isNotEqualTo(0);
        assertThat(readCount(journalDir, "llm_calls.txt"))
            .as("run1: both llm-0 and llm-1 executed")
            .isEqualTo(2);
        assertThat(readCount(journalDir, "tool_a_calls.txt"))
            .as("run1: checkFileA executed once")
            .isEqualTo(1);
        assertThat(journalEntryCount(journalDir))
            .as("run1: 3 journal entries (llm-0, tool-checkFileA, llm-1)")
            .isEqualTo(3);

        Files.writeString(filePathB, "exists");

        var run2 = launchMultiRoundRunner(filePathA, filePathB, journalDir);
        assertThat(run2.exitCode).as("run2 should succeed:\n%s", run2.stdout).isEqualTo(0);
        assertThat(run2.stdout).contains("SUCCESS");
        assertThat(readCount(journalDir, "llm_calls.txt"))
            .as("run2: only llm-2 is live; llm-0 and llm-1 replayed from journal")
            .isEqualTo(1);
        assertThat(readCount(journalDir, "tool_a_calls.txt"))
            .as("run2: checkFileA replayed from journal, NOT re-executed")
            .isEqualTo(0);
        assertThat(readCount(journalDir, "tool_b_calls.txt"))
            .as("run2: checkFileB executed (was not journaled in run1)")
            .isEqualTo(1);
        assertThat(journalEntryCount(journalDir))
            .as("run2: all 5 steps journaled (llm-0, toolA, llm-1, toolB, llm-2)")
            .isEqualTo(5);
    }

    private ProcessResult launchMultiRoundRunner(Path filePathA, Path filePathB, Path journalDir) throws Exception {
        var classpath = System.getProperty("java.class.path");
        var argfile = tempDir.resolve("argfile_" + System.nanoTime() + ".txt");
        Files.writeString(argfile,
            "--enable-preview\n" +
            "-Duser.timezone=UTC\n" +
            "-Dblackbox.file.path.a=" + filePathA.toAbsolutePath() + "\n" +
            "-Dblackbox.file.path.b=" + filePathB.toAbsolutePath() + "\n" +
            "-Dblackbox.journal.dir=" + journalDir.toAbsolutePath() + "\n" +
            "-cp\n" + classpath + "\n" +
            JournaledChatMultiRoundRunner.class.getName() + "\n"
        );

        var process = new ProcessBuilder(
            ProcessHandle.current().info().command().orElse("java"),
            "@" + argfile.toAbsolutePath()
        ).redirectErrorStream(true).start();

        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        return new ProcessResult(exitCode, output, output);
    }

    private int readCount(Path dir, String fileName) throws IOException {
        return Integer.parseInt(Files.readString(dir.resolve(fileName)).trim());
    }

    private long journalEntryCount(Path dir) throws IOException {
        try (var files = Files.list(dir)) {
            return files.filter(p -> p.toString().endsWith(".json")).count();
        }
    }

    @Test
    void streamingReplayReEmitsPreviousSessionTokens() throws Exception {
        var filePath = tempDir.resolve("signal_stream.txt");
        var journalDir = tempDir.resolve("journal_stream");
        Files.createDirectories(journalDir);
        Files.writeString(filePath, "exists");

        // Run 1: call() populates the journal fully (3 entries: llm-0, tool, llm-1)
        var run1 = launchStreamRunner(filePath, journalDir, "call");
        assertThat(run1.exitCode).as("run1 (call) should succeed:\n%s", run1.stdout).isEqualTo(0);
        assertThat(readCount(journalDir, "llm_calls.txt")).isEqualTo(2);
        assertThat(journalEntryCount(journalDir)).isEqualTo(3);

        // Run 2: stream() replays ALL steps from journal — no LLM calls, no tool calls
        // emitCachedRound should re-emit text from the tool-use round + final text round
        var run2 = launchStreamRunner(filePath, journalDir, "stream");
        assertThat(run2.exitCode).as("run2 (stream) should succeed:\n%s", run2.stdout).isEqualTo(0);
        assertThat(readCount(journalDir, "llm_calls.txt"))
            .as("run2: zero LLM calls (all replayed from journal)")
            .isEqualTo(0);

        var streamedTokens = Files.readString(journalDir.resolve("streamed_tokens.txt"));
        assertThat(streamedTokens)
            .as("stream should re-emit tool-call marker from cached round 0")
            .contains("[tool_call] checkFile");
        assertThat(streamedTokens)
            .as("stream should re-emit final text from cached round 1")
            .contains("File exists and is accessible.");
    }

    private ProcessResult launchStreamRunner(Path filePath, Path journalDir, String mode) throws Exception {
        var classpath = System.getProperty("java.class.path");
        var argfile = tempDir.resolve("argfile_" + System.nanoTime() + ".txt");
        Files.writeString(argfile,
            "--enable-preview\n" +
            "-Duser.timezone=UTC\n" +
            "-Dblackbox.file.path=" + filePath.toAbsolutePath() + "\n" +
            "-Dblackbox.journal.dir=" + journalDir.toAbsolutePath() + "\n" +
            "-Dblackbox.mode=" + mode + "\n" +
            "-cp\n" + classpath + "\n" +
            JournaledChatStreamRunner.class.getName() + "\n"
        );

        var process = new ProcessBuilder(
            ProcessHandle.current().info().command().orElse("java"),
            "@" + argfile.toAbsolutePath()
        ).redirectErrorStream(true).start();

        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        return new ProcessResult(exitCode, output, output);
    }

    @Test
    void doubleStreamCheckpointRestore() throws Exception {
        var filePath = tempDir.resolve("signal_ds.txt");
        var journalDir = tempDir.resolve("journal_ds");
        Files.createDirectories(journalDir);

        // Run 1 (stream): round 0 streams OK, tool crashes (file missing)
        var run1 = launchDoubleStreamRunner(filePath, journalDir);
        assertThat(run1.exitCode).as("run1 should fail:\n%s", run1.stdout).isNotEqualTo(0);
        assertThat(readCount(journalDir, "llm_calls.txt"))
            .as("run1: 1 streaming LLM call (round 0)")
            .isEqualTo(1);
        assertThat(journalEntryCount(journalDir))
            .as("run1: only llm-stream-0 journaled")
            .isEqualTo(1);

        Files.writeString(filePath, "exists");

        // Run 2 (stream): round 0 replayed via emitCachedRound, tool succeeds, round 1 streams live
        var run2 = launchDoubleStreamRunner(filePath, journalDir);
        assertThat(run2.exitCode).as("run2 should succeed:\n%s", run2.stdout).isEqualTo(0);
        assertThat(readCount(journalDir, "llm_calls.txt"))
            .as("run2: only 1 live streaming LLM call (round 1); round 0 replayed from journal")
            .isEqualTo(1);

        var streamedTokens = Files.readString(journalDir.resolve("streamed_tokens.txt"));
        assertThat(streamedTokens)
            .as("run2 should re-emit tool-call marker from cached round 0")
            .contains("[tool_call] checkFile");
        assertThat(streamedTokens)
            .as("run2 should contain live-streamed text from round 1")
            .contains("File exists and is accessible.");
    }

    private ProcessResult launchDoubleStreamRunner(Path filePath, Path journalDir) throws Exception {
        var classpath = System.getProperty("java.class.path");
        var argfile = tempDir.resolve("argfile_" + System.nanoTime() + ".txt");
        Files.writeString(argfile,
            "--enable-preview\n" +
            "-Duser.timezone=UTC\n" +
            "-Dblackbox.file.path=" + filePath.toAbsolutePath() + "\n" +
            "-Dblackbox.journal.dir=" + journalDir.toAbsolutePath() + "\n" +
            "-cp\n" + classpath + "\n" +
            JournaledChatDoubleStreamRunner.class.getName() + "\n"
        );

        var process = new ProcessBuilder(
            ProcessHandle.current().info().command().orElse("java"),
            "@" + argfile.toAbsolutePath()
        ).redirectErrorStream(true).start();

        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        return new ProcessResult(exitCode, output, output);
    }

    record ProcessResult(int exitCode, String stdout, String stderr) {}
}
