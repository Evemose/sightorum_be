package com.rorm.ai.swarm.agents;

import com.rorm.ai.chat.AiChatService;
import com.rorm.ai.chat.ChatProgress;
import com.rorm.ai.chat.ChatRequest;
import com.rorm.ai.chat.ThinkingLevel;
import com.rorm.ai.swarm.SwarmConfig.ModelConfig;
import lombok.AccessLevel;
import lombok.Getter;
import org.jspecify.annotations.Nullable;

public class SecondarySwarmAgent {

    private final String modelName;
    private final String promptTemplate;
    private final AiChatService chatService;
    private final ChatProgress chatProgress;
    private final ThinkingLevel thinkingLevel;
    @Getter(lazy = true, value = AccessLevel.PRIVATE)
    private final String systemPrompt = buildSystemPrompt();

    public SecondarySwarmAgent(
        ModelConfig modelConfig,
        AiChatService chatService,
        ChatProgress chatProgress,
        ThinkingLevel thinkingLevel
    ) {
        this.modelName = modelConfig.model();
        this.promptTemplate = modelConfig.systemPrompt();
        this.chatService = chatService;
        this.chatProgress = chatProgress;
        this.thinkingLevel = thinkingLevel;
    }

    public <T> T call(String input, @Nullable String conversationId, Class<T> responseType) {
        return chatService.call(buildRequest(input, conversationId, responseType));
    }

    private <T> ChatRequest<T> buildRequest(String input, @Nullable String conversationId, Class<T> responseType) {
        return ChatRequest.usingData("", chatProgress.getModelSpace())
            .withSystemPrompt(getSystemPrompt())
            .withThinkingLevel(thinkingLevel)
            .withModelName(modelName)
            .withChatId(conversationId)
            .ask(input, responseType);
    }

    public <T> T call(String input, Class<T> responseType) {
        return chatService.call(buildRequest(input, null, responseType));
    }

    private String buildSystemPrompt() {
        // In a real implementation, this would use the promptTemplate and possibly other context to build the system prompt
        return promptTemplate;
    }

}
