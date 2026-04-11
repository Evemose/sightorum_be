package com.rorm.ai.swarm.agents;

import com.rorm.ai.chat.*;
import com.rorm.ai.chat.ChatRequest.Builder;
import com.rorm.ai.prompt.PromptPlaceholders;
import com.rorm.ai.swarm.AgentModelConfig;
import com.rorm.metamodel.ModelSpace;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

public class FirstLevelSwarmAgent {

    private static final String USER_QUERY_PLACEHOLDER = "USER_QUERY";

    private final String modelName;
    private final String promptTemplate;
    private final AiChatService chatService;
    private final String schema;
    private final ModelSpace modelSpace;
    private final ThinkingLevel thinkingLevel;
    private final Set<ToolGroup> toolGroups;
    private final @Nullable CacheStrategy cacheStrategy;
    private final SwarmPromptTemplateRenderer promptRenderer;

    public FirstLevelSwarmAgent(
        AgentModelConfig config,
        AiChatService chatService,
        String schema,
        ModelSpace modelSpace,
        PromptPlaceholders promptPlaceholders
    ) {
        this.modelName = config.model();
        this.promptTemplate = config.systemPrompt();
        this.chatService = chatService;
        this.schema = schema;
        this.modelSpace = modelSpace;
        this.thinkingLevel = config.thinkingLevel();
        this.toolGroups = config.toolGroups() != null ? config.toolGroups() : Set.of();
        this.cacheStrategy = config.cacheStrategy();
        this.promptRenderer = new SwarmPromptTemplateRenderer(promptPlaceholders, modelSpace);
    }

    public Flux<StreamToken> streamTokens(String input, UnaryOperator<Builder> requestBuilderCustomizer) {
        return chatService.streamTokens(buildRequest(input, null, requestBuilderCustomizer));
    }

    private ChatRequest<String> buildRequest(String input, @Nullable String conversationId,
                                             UnaryOperator<Builder> requestBuilderCustomizer) {
        var builder = ChatRequest.usingData(schema, modelSpace)
            .withSystemPrompt(buildSystemPrompt(input))
            .withThinkingLevel(thinkingLevel)
            .withModelName(modelName)
            .withChatId(conversationId);
        if (!toolGroups.isEmpty()) {
            builder = builder.withToolGroups(toolGroups.toArray(new ToolGroup[0]));
        }
        if (cacheStrategy != null) {
            builder = builder.withCachingStrategyFunction(cacheStrategy);
        }
        return requestBuilderCustomizer.apply(builder).ask(input);
    }

    private String buildSystemPrompt(String input) {
        return promptRenderer.render(promptTemplate, Map.of(USER_QUERY_PLACEHOLDER, input));
    }

}
