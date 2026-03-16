package com.rorm.ai.chat;

import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import reactor.core.publisher.Flux;

import java.io.File;
import java.sql.DriverManager;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Standalone process that runs a durable chat session.
 * Reads config from system properties. Exits 0 on success, 1 on failure.
 * Invoked by {@link DurableChatBlackboxTest} as a subprocess.
 */
public class DurableChatBlackboxRunner {

    public static void main(String[] args) {
        try {
            var jdbcUrl = System.getProperty("blackbox.jdbc.url");
            var filePath = System.getProperty("blackbox.file.path");
            var conversationId = System.getProperty("blackbox.conversation.id");

            initSchema(jdbcUrl);

            var ds = new DriverManagerDataSource(jdbcUrl, "test", "test");
            var repo = JdbcChatMemoryRepository.builder().dataSource(ds).build();
            var chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(repo)
                .maxMessages(100)
                .build();

            var service = new DurableChatService(new ScriptedChatModel(), chatMemory);

            var toolCallbacks = Arrays.stream(
                MethodToolCallbackProvider.builder()
                    .toolObjects(new FileCheckTool(filePath))
                    .build()
                    .getToolCallbacks()
            ).collect(Collectors.toMap(cb -> cb.getToolDefinition().name(), Function.identity()));

            var response = service.chat(
                conversationId,
                "Check the file please",
                List.of(new SystemMessage("You are a test assistant.")),
                ChatOptions.builder().build(),
                toolCallbacks
            );

            if (!response.getResult().getOutput().getText().contains("File exists")) {
                System.err.println("Unexpected response: " + response.getResult().getOutput().getText());
                System.exit(1);
            }

            System.exit(0);
        } catch (Exception e) {
            System.err.println("Runner failed: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void initSchema(String jdbcUrl) throws Exception {
        try (var conn = DriverManager.getConnection(jdbcUrl, "test", "test");
             var stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS SPRING_AI_CHAT_MEMORY (
                    conversation_id VARCHAR(256) NOT NULL,
                    content TEXT NOT NULL,
                    type VARCHAR(100) NOT NULL,
                    "timestamp" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )""");
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_chat_memory_conv_ts
                ON SPRING_AI_CHAT_MEMORY(conversation_id, "timestamp")""");
        }
    }

    static class FileCheckTool {
        private final String filePath;
        FileCheckTool(String filePath) { this.filePath = filePath; }

        @Tool(description = "Check if a file exists at the configured path")
        public String checkFile() {
            var file = new File(filePath);
            if (!file.exists()) throw new RuntimeException("File not found: " + filePath);
            return "File exists: " + filePath + " (size: " + file.length() + " bytes)";
        }
    }

    static class ScriptedChatModel implements ChatModel {
        @Override
        public ChatResponse call(Prompt prompt) {
            boolean hasToolResult = prompt.getInstructions().stream()
                .anyMatch(m -> m instanceof org.springframework.ai.chat.messages.ToolResponseMessage);
            if (hasToolResult) {
                return new ChatResponse(List.of(new Generation(
                    new AssistantMessage("File exists and is accessible."))));
            }
            return new ChatResponse(List.of(new Generation(
                AssistantMessage.builder()
                    .content("Let me check that file for you.")
                    .toolCalls(List.of(new AssistantMessage.ToolCall(
                        UUID.randomUUID().toString(), "function", "checkFile", "{}")))
                    .build())));
        }

        @Override public Flux<ChatResponse> stream(Prompt prompt) { return Flux.just(call(prompt)); }
        @Override public ChatOptions getDefaultOptions() { return ChatOptions.builder().build(); }
    }
}
