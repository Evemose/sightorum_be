package com.rorm.ai.swarm.agents;

import com.rorm.ai.chat.AiChatService;
import com.rorm.ai.chat.ChatRequest;
import com.rorm.ai.chat.ThinkingLevel;
import com.rorm.ai.prompt.PromptPlaceholders;
import com.rorm.ai.swarm.SwarmConfig.ModelConfig;
import com.rorm.metamodel.ModelSpace;
import org.jspecify.annotations.Nullable;

import java.util.Map;

public class SecondarySwarmAgent {

    private static final String DTO_TYPE_PLACEHOLDER = "DTO_TYPE";
    private static final String RAW_OUTPUT_PLACEHOLDER = "RAW_OUTPUT";

    private final String modelName;
    private final String promptTemplate;
    private final AiChatService chatService;
    private final String schema;
    private final ModelSpace modelSpace;
    private final ThinkingLevel thinkingLevel;
    private final SwarmPromptTemplateRenderer promptRenderer;

    public SecondarySwarmAgent(
        ModelConfig modelConfig,
        AiChatService chatService,
        String schema,
        ModelSpace modelSpace,
        ThinkingLevel thinkingLevel,
        PromptPlaceholders promptPlaceholders
    ) {
        this.modelName = modelConfig.model();
        this.promptTemplate = modelConfig.systemPrompt();
        this.chatService = chatService;
        this.schema = schema;
        this.modelSpace = modelSpace;
        this.thinkingLevel = thinkingLevel;
        this.promptRenderer = new SwarmPromptTemplateRenderer(promptPlaceholders, modelSpace);
    }

    public <T> T call(String input, @Nullable String conversationId, Class<T> responseType) {
        return chatService.call(buildRequest(input, conversationId, responseType));
    }

    private <T> ChatRequest<T> buildRequest(String input, @Nullable String conversationId, Class<T> responseType) {
        return ChatRequest.usingData(schema, modelSpace)
            .withSystemPrompt(buildSystemPrompt(input, responseType))
            .withThinkingLevel(thinkingLevel)
            .withModelName(modelName)
            .withChatId(conversationId)
            .ask(input, responseType);
    }

    public <T> T call(String input, Class<T> responseType) {
        return chatService.call(buildRequest(input, null, responseType));
    }

    private String buildSystemPrompt(String input, Class<?> responseType) {
        return promptRenderer.render(promptTemplate, Map.of(
            DTO_TYPE_PLACEHOLDER, responseType.getSimpleName(),
            RAW_OUTPUT_PLACEHOLDER, input
        ));
    }

}
