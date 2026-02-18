package com.rorm.ai.swarm.agents;

import com.rorm.ai.chat.AiChatService;
import com.rorm.ai.chat.ChatRequest;
import com.rorm.ai.chat.ChatRequest.Builder;
import com.rorm.ai.chat.ThinkingLevel;
import com.rorm.ai.swarm.SwarmConfig.ModelConfig;
import com.rorm.metamodel.ModelSpace;
import lombok.AccessLevel;
import lombok.Getter;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;

import java.util.function.UnaryOperator;

public class FirstLevelSwarmAgent {

    private final String modelName;
    private final String promptTemplate;
    private final AiChatService chatService;
    private final ModelSpace modelSpace;
    private final ThinkingLevel thinkingLevel;
    @Getter(lazy = true, value = AccessLevel.PRIVATE)
    private final String systemPrompt = buildSystemPrompt();

    public FirstLevelSwarmAgent(
        ModelConfig modelConfig,
        AiChatService chatService,
        ModelSpace modelSpace,
        ThinkingLevel thinkingLevel
    ) {
        this.modelName = modelConfig.model();
        this.promptTemplate = modelConfig.systemPrompt();
        this.chatService = chatService;
        this.modelSpace = modelSpace;
        this.thinkingLevel = thinkingLevel;
    }

    public Flux<String> stream(String input, String conversationId) {
        return chatService.stream(buildRequest(input, conversationId, UnaryOperator.identity()));
    }

    private ChatRequest<String> buildRequest(String input, @Nullable String conversationId, UnaryOperator<Builder> requestBuilderCustomizer) {
        return requestBuilderCustomizer.apply(
            ChatRequest.usingData("", modelSpace)
                .withSystemPrompt(getSystemPrompt())
                .withThinkingLevel(thinkingLevel)
                .withModelName(modelName)
                .withChatId(conversationId)
        ).ask(input);
    }

    public Flux<String> stream(String input, UnaryOperator<Builder> requestBuilderCustomizer) {
        return chatService.stream(buildRequest(input, null, requestBuilderCustomizer));
    }

    private String buildSystemPrompt() {
        return promptTemplate;
    }

}
