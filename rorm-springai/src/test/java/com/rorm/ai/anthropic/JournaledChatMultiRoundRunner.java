package com.rorm.ai.anthropic;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class JournaledChatMultiRoundRunner {

    static void main(String[] args) {
        var journalDir = System.getProperty("blackbox.journal.dir");
        var llmCallCount = new AtomicInteger();
        var toolACallCount = new AtomicInteger();
        var toolBCallCount = new AtomicInteger();
        try {
            var filePathA = System.getProperty("blackbox.file.path.a");
            var filePathB = System.getProperty("blackbox.file.path.b");
            var client = ScriptedAnthropicClient.multiRoundClient(llmCallCount);
            var model = new JournaledAnthropicChatModel(client, new AnthropicParamsBuilder(new ObjectMapper()), null, null);
            var journal = new FileStepJournal(Path.of(journalDir));

            var options = AnthropicChatOptions.builder()
                .toolCallbacks(List.of(
                    ScriptedAnthropicClient.trackedFileCheckCallback("checkFileA", filePathA, toolACallCount),
                    ScriptedAnthropicClient.trackedFileCheckCallback("checkFileB", filePathB, toolBCallCount)
                ))
                .journal(journal)
                .build();
            var prompt = new Prompt(List.of(new UserMessage("Check both files please")), options);

            var result = model.call(prompt);

            writeCounts(journalDir, llmCallCount, toolACallCount, toolBCallCount);

            var text = result.getResult().getOutput().getText();
            if (text.contains("Both files exist")) {
                System.out.println("SUCCESS: " + text);
                System.exit(0);
            } else {
                System.err.println("UNEXPECTED: " + text);
                System.exit(1);
            }
        } catch (Throwable e) {
            try {
                writeCounts(journalDir, llmCallCount, toolACallCount, toolBCallCount);
            } catch (Exception ignored) {
            }
            System.err.println("FAILED: " + e.getMessage());
            System.err.flush();
            System.exit(1);
        }
    }

    private static void writeCounts(String dir, AtomicInteger llm, AtomicInteger toolA, AtomicInteger toolB) throws Exception {
        Files.writeString(Path.of(dir, "llm_calls.txt"), String.valueOf(llm.get()));
        Files.writeString(Path.of(dir, "tool_a_calls.txt"), String.valueOf(toolA.get()));
        Files.writeString(Path.of(dir, "tool_b_calls.txt"), String.valueOf(toolB.get()));
    }
}
