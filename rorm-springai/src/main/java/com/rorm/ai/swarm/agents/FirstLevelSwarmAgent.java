package com.rorm.ai.swarm.agents;

import com.rorm.ai.chat.AiChatService;
import com.rorm.ai.chat.ChatProgress;
import com.rorm.ai.chat.ChatRequest;
import com.rorm.ai.chat.ThinkingLevel;
import com.rorm.ai.swarm.SwarmConfig.ModelConfig;
import lombok.AccessLevel;
import lombok.Getter;
import reactor.core.publisher.Flux;

public class FirstLevelSwarmAgent {

    private final String modelName;
    private final String promptTemplate;
    private final AiChatService chatService;
    private final ChatProgress chatProgress;
    private final ThinkingLevel thinkingLevel;
    @Getter(lazy = true, value = AccessLevel.PRIVATE)
    private final String systemPrompt = buildSystemPrompt();

    public FirstLevelSwarmAgent(
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

    public Flux<String> stream(String input, String conversationId) {
        return chatService.stream(buildRequest(input, conversationId));
    }

    private ChatRequest<String> buildRequest(String input, String conversationId) {
        return ChatRequest.usingData("", chatProgress.getModelSpace())
            .withSystemPrompt(getSystemPrompt())
            .withThinkingLevel(thinkingLevel)
            .withModelName(modelName)
            .withChatId(conversationId)
            .ask(input);
    }

    private String buildSystemPrompt() {
        // In a real implementation, this would use the promptTemplate and possibly other context to build the system prompt
        return promptTemplate;
    }

}
