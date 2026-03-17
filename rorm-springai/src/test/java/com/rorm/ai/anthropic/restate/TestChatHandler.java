package com.rorm.ai.anthropic.restate;

import com.rorm.ai.anthropic.AnthropicChatOptions;
import com.rorm.ai.anthropic.ScriptedAnthropicClient;
import com.rorm.ml.restate.RestateStepJournal;
import dev.restate.sdk.ObjectContext;
import dev.restate.sdk.annotation.Exclusive;
import dev.restate.sdk.annotation.Name;
import dev.restate.sdk.springboot.RestateVirtualObject;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

@RestateVirtualObject
@Name("TestChatHandler")
@RequiredArgsConstructor
public class TestChatHandler {

    private final ChatModel chatModel;

    @SneakyThrows
    @Exclusive
    public String chat(ObjectContext ctx, String userMessage) {
        try {
            var filePath = System.getProperty("blackbox.file.path");
            var options = AnthropicChatOptions.builder()
                .toolCallbacks(List.of(ScriptedAnthropicClient.fileCheckCallback(filePath)))
                .journal(new RestateStepJournal(ctx))
                .build();
            var response = chatModel.call(new Prompt(List.of(new UserMessage(userMessage)), options));
            return response.getResult().getOutput().getText();
        } finally {
            var dir = System.getProperty("blackbox.journal.dir");
            if (dir != null) {
                java.nio.file.Files.writeString(java.nio.file.Path.of(dir, "llm_calls.txt"),
                    String.valueOf(RestateCheckpointTestApp.LLM_CALL_COUNT.get()));
            }
        }
    }
}
