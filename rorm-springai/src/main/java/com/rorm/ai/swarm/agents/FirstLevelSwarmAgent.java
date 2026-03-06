package com.rorm.ai.swarm.agents;

import com.rorm.ai.chat.AiChatService;
import com.rorm.ai.chat.ChatRequest;
import com.rorm.ai.chat.ChatRequest.Builder;
import com.rorm.ai.chat.ThinkingLevel;
import com.rorm.ai.prompt.PromptPlaceholders;
import com.rorm.ai.swarm.SwarmConfig.ModelConfig;
import com.rorm.metamodel.ModelSpace;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.function.UnaryOperator;

public class FirstLevelSwarmAgent {

    private static final String USER_QUERY_PLACEHOLDER = "USER_QUERY";

    private final String modelName;
    private final String promptTemplate;
    private final AiChatService chatService;
    private final String schema;
    private final ModelSpace modelSpace;
    private final ThinkingLevel thinkingLevel;
    private final SwarmPromptTemplateRenderer promptRenderer;

    public FirstLevelSwarmAgent(
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

    public Flux<String> stream(String input, String conversationId) {
        return chatService.stream(buildRequest(input, conversationId, UnaryOperator.identity()));
    }

    private ChatRequest<String> buildRequest(String input, @Nullable String conversationId, UnaryOperator<Builder> requestBuilderCustomizer) {
        return requestBuilderCustomizer.apply(
            ChatRequest.usingData(schema, modelSpace)
                .withSystemPrompt(buildSystemPrompt(input))
                .withThinkingLevel(thinkingLevel)
                .withModelName(modelName)
                .withChatId(conversationId)
        ).ask(input);
    }

    public Flux<String> stream(String input, UnaryOperator<Builder> requestBuilderCustomizer) {
        return chatService.stream(buildRequest(input, null, requestBuilderCustomizer));
    }

    private String buildSystemPrompt(String input) {
        return promptRenderer.render(promptTemplate, Map.of(USER_QUERY_PLACEHOLDER, input));
    }

}
