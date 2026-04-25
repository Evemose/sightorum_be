package com.rorm.client.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.ai.chat.*;
import com.rorm.client.chat.tool.CausalAnalysisTool;
import com.rorm.client.chat.tool.DescriptiveAnalysisTool;
import com.rorm.client.chat.tool.ImportTool;
import com.rorm.client.chat.tool.LookupTool;
import com.rorm.client.metamodel.MetamodelService;
import com.rorm.client.stream.SseEmitterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final AiChatService aiChatService;
    private final MetamodelService metamodelService;
    private final SseEmitterRegistry sseRegistry;
    private final CausalAnalysisTool causalAnalysisTool;
    private final DescriptiveAnalysisTool descriptiveAnalysisTool;
    private final ImportTool importTool;
    private final LookupTool lookupTool;
    private final String systemPrompt = buildSystemPrompt();

    @SneakyThrows
    private String buildSystemPrompt() {
        String base;
        try (var in = new ClassPathResource("prompts/chat/copilot-system.txt").getInputStream()) {
            base = StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        }
        var schemaJson = new ObjectMapper()
            .writerWithDefaultPrettyPrinter()
            .writeValueAsString(ChatNodeSchema.SCHEMA);
        return base
               + "\n\nRESPONSE FORMAT\n---------------\n"
               + "Your entire response MUST be a single JSON object that validates against\n"
               + "the schema below. No prose outside the JSON. No markdown code fences around\n"
               + "the JSON. Stream the JSON directly. The shape is `{ \"nodes\": [ChatNode, ...] }`\n"
               + "where each ChatNode is discriminated by its `type` field.\n\n"
               + "```json\n" + schemaJson + "\n```\n";
    }

    public void streamResponse(String schema, String sessionId, String chatId, String message) {
        var topic = chatTopic(chatId);
        var modelSpace = metamodelService.getModelSpace(schema);

        var chatRequest = ChatRequest.usingData(schema, modelSpace)
            .withToolGroups(ToolGroup.QUERY, ToolGroup.STATS)
            .withThinkingLevel(ThinkingLevel.MEDIUM)
            .withSystemPrompt(systemPrompt)
            .withMemoryIncludes(MemoryInclude.TOOL_CALLS, MemoryInclude.TOOL_RESPONSES)
            .withChatId(chatId)
            .withSessionId(sessionId)
            .withToolContextEntry("sessionId", sessionId)
            .withTool(causalAnalysisTool)
            .withTool(descriptiveAnalysisTool)
            .withTool(importTool)
            .withTool(lookupTool)
            .ask(message);

        var counter = new AtomicInteger(0);
        aiChatService.streamTokens(chatRequest)
            .subscribe(
                token -> sseRegistry.publish(topic, tokenEventName(token),
                    String.valueOf(counter.incrementAndGet()), token),
                error -> {
                    log.error("Chat stream error for session {}", sessionId, error);
                    sseRegistry.error(topic, error.getMessage());
                },
                () -> sseRegistry.complete(topic)
            );
    }

    static String chatTopic(String chatId) {
        return "chat:" + chatId;
    }

    private String tokenEventName(StreamToken token) {
        return switch (token) {
            case StreamToken.Text _ -> "text";
            case StreamToken.Thinking _ -> "thinking";
            case StreamToken.ToolCall _ -> "tool_call";
            case StreamToken.ServerTool _ -> "server_tool";
            case StreamToken.SearchResult _ -> "search_result";
            case StreamToken.Citation _ -> "citation";
        };
    }
}
