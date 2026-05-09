package com.rorm.ai.anthropic;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class JournaledChatDoubleStreamRunner {

    static void main(String[] args) {
        var journalDir = System.getProperty("blackbox.journal.dir");
        var llmCallCount = new AtomicInteger();
        try {
            var filePath = System.getProperty("blackbox.file.path");
            var client = ScriptedAnthropicClient.withStreamingCallCounter(llmCallCount);
            var model = new JournaledAnthropicChatModel(client, client, new AnthropicParamsBuilder(new ObjectMapper()), null, null, (m, e) -> java.util.Optional.empty());
            var journal = new FileStepJournal(Path.of(journalDir));

            var options = AnthropicChatOptions.builder()
                .toolCallbacks(List.of(ScriptedAnthropicClient.fileCheckCallback(filePath)))
                .journal(journal)
                .build();
            var prompt = new Prompt(List.of(new UserMessage("Check the file please")), options);

            var tokens = new StringBuilder();
            model.stream(prompt)
                .map(r -> r.getResult().getOutput().getText())
                .filter(t -> t != null && !t.isEmpty())
                .doOnNext(tokens::append)
                .blockLast();

            Files.writeString(Path.of(journalDir, "llm_calls.txt"),
                String.valueOf(llmCallCount.get()));
            Files.writeString(Path.of(journalDir, "streamed_tokens.txt"), tokens.toString());

            if (tokens.toString().contains("File exists")) {
                System.out.println("SUCCESS");
                System.exit(0);
            } else {
                System.err.println("UNEXPECTED: " + tokens);
                System.exit(1);
            }
        } catch (Throwable e) {
            try {
                Files.writeString(Path.of(journalDir, "llm_calls.txt"),
                    String.valueOf(llmCallCount.get()));
            } catch (Exception ignored) {
            }
            System.err.println("FAILED: " + e.getMessage());
            e.printStackTrace(System.err);
            System.err.flush();
            System.exit(1);
        }
    }
}
