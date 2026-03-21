package com.rorm.ai.chat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
public class JsonbChatMemoryRepository implements ChatMemoryRepository {

    private static final String THINKING = "THINKING";
    private static final String SERVER_TOOL = "SERVER_TOOL";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public List<String> findConversationIds() {
        return jdbcTemplate.queryForList(
            "select distinct conversation_id from chat_memory order by conversation_id",
            String.class
        );
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        return jdbcTemplate.query(
            "select message_type, payload from chat_memory where conversation_id = ? order by created_at",
            (rs, _) -> toMessage(rs.getString("message_type"), readPayload(rs.getString("payload"))),
            conversationId
        );
    }

    private static Message toMessage(String type, Payload payload) {
        var text = payload.text() != null ? payload.text() : "";
        var metadata = payload.metadata() != null ? payload.metadata() : Map.<String, Object>of();

        return switch (type) {
            case "USER" -> UserMessage.builder().text(text).metadata(metadata).build();
            case "SYSTEM" -> SystemMessage.builder().text(text).metadata(metadata).build();
            case "ASSISTANT" -> {
                var toolCalls = payload.toolCalls() != null
                    ? payload.toolCalls().stream()
                    .map(tc -> new AssistantMessage.ToolCall(tc.id(), tc.type(), tc.name(), tc.arguments()))
                    .toList()
                    : List.<AssistantMessage.ToolCall>of();
                yield AssistantMessage.builder().content(text).properties(metadata)
                    .toolCalls(toolCalls).build();
            }
            case "TOOL" -> {
                var responses = payload.toolResponses() != null
                    ? payload.toolResponses().stream()
                    .map(tr -> new ToolResponseMessage.ToolResponse(tr.id(), tr.name(), tr.responseData()))
                    .toList()
                    : List.<ToolResponseMessage.ToolResponse>of();
                yield ToolResponseMessage.builder().responses(responses).metadata(metadata).build();
            }
            case THINKING -> new ThinkingMessage(text);
            case SERVER_TOOL -> new ServerToolMessage(
                (String) metadata.getOrDefault("toolName", "unknown"),
                (String) metadata.get("inputJson"),
                (String) metadata.get("outputJson")
            );
            default -> throw new IllegalArgumentException("Unknown message type: " + type);
        };
    }

    private Payload readPayload(String json) {
        try {
            return objectMapper.readValue(json, Payload.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize chat message payload", e);
        }
    }

    @Override
    @Transactional
    public void saveAll(String conversationId, List<Message> messages) {
        jdbcTemplate.update("delete from chat_memory where conversation_id = ?", conversationId);
        jdbcTemplate.batchUpdate(
            "insert into chat_memory (conversation_id, message_type, payload) values (?, ?, ?::jsonb)",
            messages, messages.size(),
            (ps, message) -> {
                ps.setString(1, conversationId);
                ps.setString(2, messageTypeOf(message));
                ps.setString(3, writePayload(message));
            }
        );
    }

    private static String messageTypeOf(Message message) {
        return switch (message) {
            case ThinkingMessage _ -> THINKING;
            case ServerToolMessage _ -> SERVER_TOOL;
            default -> message.getMessageType().name();
        };
    }

    private String writePayload(Message message) {
        var metadata = new HashMap<>(message.getMetadata());
        metadata.remove("messageType");

        List<ToolCallDto> toolCalls = null;
        if (message instanceof AssistantMessage am && !am.getToolCalls().isEmpty()) {
            toolCalls = am.getToolCalls().stream()
                .map(tc -> new ToolCallDto(tc.id(), tc.type(), tc.name(), tc.arguments()))
                .toList();
        }

        List<ToolResponseDto> toolResponses = null;
        if (message instanceof ToolResponseMessage trm) {
            toolResponses = trm.getResponses().stream()
                .map(tr -> new ToolResponseDto(tr.id(), tr.name(), tr.responseData()))
                .toList();
        }

        if (message instanceof ServerToolMessage(String toolName, String inputJson, String outputJson)) {
            metadata.put("toolName", toolName);
            if (inputJson != null) {
                metadata.put("inputJson", inputJson);
            }
            if (outputJson != null) {
                metadata.put("outputJson", outputJson);
            }
        }

        try {
            return objectMapper.writeValueAsString(new Payload(
                message.getText(),
                metadata.isEmpty() ? null : metadata,
                toolCalls,
                toolResponses
            ));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize chat message", e);
        }
    }

    // -- type discriminator --

    @Override
    public void deleteByConversationId(String conversationId) {
        jdbcTemplate.update("delete from chat_memory where conversation_id = ?", conversationId);
    }

    // -- serialization --

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Payload(
        String text,
        Map<String, Object> metadata,
        List<ToolCallDto> toolCalls,
        List<ToolResponseDto> toolResponses
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ToolCallDto(String id, String type, String name, String arguments) {}

    // -- deserialization --

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ToolResponseDto(String id, String name, String responseData) {}
}
