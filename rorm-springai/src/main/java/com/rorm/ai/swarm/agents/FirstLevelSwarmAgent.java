package com.rorm.ai.swarm.agents;

import com.rorm.ai.chat.*;
import com.rorm.ai.chat.ChatRequest.Builder;
import com.rorm.ai.prompt.PromptPlaceholders;
import com.rorm.ai.swarm.AgentModelConfig;
import com.rorm.metamodel.ModelSpace;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.ClassPathResource;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

public class FirstLevelSwarmAgent {

    private static final String USER_QUERY_PLACEHOLDER = "USER_QUERY";

    /**
     * Tools every first-level agent gets unconditionally: peer-query
     * (so any agent can ask any role a question) and knowledge-store
     * access (so any agent can record and recall dataset memory).
     */
    private static final Set<ToolGroup> DEFAULT_TOOL_GROUPS =
        Set.of(ToolGroup.PEER_QUERY, ToolGroup.KNOWLEDGE_STORE);

    /**
     * Mandatory system-prompt suffix appended after the role's own
     * system prompt — documents the always-available peer-query and
     * knowledge-store tools so every agent knows when and how to call
     * them without each role re-stating the contract.
     */
    private static final String SHARED_TOOLS_SUFFIX = loadSharedToolsSuffix();

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
        this.toolGroups = mergeWithDefaults(config.toolGroups());
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

    private static Set<ToolGroup> mergeWithDefaults(@Nullable Set<ToolGroup> configured) {
        var merged = EnumSet.copyOf(DEFAULT_TOOL_GROUPS);
        if (configured != null) {
            merged.addAll(configured);
        }
        return merged;
    }

    private static String loadSharedToolsSuffix() {
        var resource = new ClassPathResource("prompts/durable-swarm/_shared-tools-suffix.txt");
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(
                "Missing mandatory shared-tools suffix prompt: " + resource.getPath(), e);
        }
    }

    private String buildSystemPrompt(String input) {
        var rendered = promptRenderer.render(promptTemplate, Map.of(USER_QUERY_PLACEHOLDER, input));
        return rendered + "\n\n" + SHARED_TOOLS_SUFFIX;
    }

}
