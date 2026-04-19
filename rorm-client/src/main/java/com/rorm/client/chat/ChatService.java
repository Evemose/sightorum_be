package com.rorm.client.chat;

import com.rorm.ai.chat.*;
import com.rorm.client.chat.tool.CausalAnalysisTool;
import com.rorm.client.chat.tool.DescriptiveAnalysisTool;
import com.rorm.client.chat.tool.ImportTool;
import com.rorm.client.chat.tool.LookupTool;
import com.rorm.client.metamodel.MetamodelService;
import com.rorm.client.stream.SseEmitterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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

    public void streamResponse(String schema, String sessionId, String message) {
        var topic = chatTopic(sessionId);
        var modelSpace = metamodelService.getModelSpace(schema);

        var chatRequest = ChatRequest.usingData(schema, modelSpace)
            .withToolGroups(ToolGroup.QUERY, ToolGroup.STATS)
            .withThinkingLevel(ThinkingLevel.MEDIUM)
            .withChatId(sessionId)
            .withSessionId(sessionId)
            .withTool(causalAnalysisTool)
            .withTool(descriptiveAnalysisTool)
            .withTool(importTool)
            .withTool(lookupTool)
            .withResponseSchema(ChatNodeSchema.SCHEMA)
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

    static String chatTopic(String sessionId) {
        return "chat:" + sessionId;
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
