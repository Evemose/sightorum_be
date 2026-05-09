package com.rorm.ai.swarm.agents;

import com.rorm.ai.chat.AiChatService;
import com.rorm.ai.chat.ChatRequest;
import com.rorm.ai.chat.ThinkingLevel;
import com.rorm.ai.prompt.PromptPlaceholders;
import com.rorm.ai.swarm.AgentModelConfig;
import com.rorm.metamodel.ModelSpace;

import java.util.Map;

/**
 * Default {@link SecondarySwarmAgent} implementation: routes the
 * first-level agent's raw text through a Haiku-class summarizer that
 * extracts the typed DTO via structured output. Used by every step that
 * does not install a specialized secondary agent (e.g. compiler with
 * its tool-driven {@code validatePipelineSpec} holder).
 */
public class SummarizingSecondaryAgent<T> implements SecondarySwarmAgent<T> {

    private static final String DTO_TYPE_PLACEHOLDER = "DTO_TYPE";
    private static final String RAW_OUTPUT_PLACEHOLDER = "RAW_OUTPUT";

    private final String modelName;
    private final String promptTemplate;
    private final AiChatService chatService;
    private final ThinkingLevel thinkingLevel;
    private final SwarmPromptTemplateRenderer promptRenderer;
    private final Class<T> responseType;

    public SummarizingSecondaryAgent(
        AgentModelConfig config,
        AiChatService chatService,
        PromptPlaceholders promptPlaceholders,
        ModelSpace modelSpace,
        Class<T> responseType
    ) {
        this.modelName = config.model();
        this.promptTemplate = config.systemPrompt();
        this.chatService = chatService;
        this.thinkingLevel = config.thinkingLevel();
        this.promptRenderer = new SwarmPromptTemplateRenderer(promptPlaceholders, modelSpace);
        this.responseType = responseType;
    }

    @Override
    public SecondaryAgentResult<T> produce(SecondaryAgentContext context) {
        var dto = chatService.call(buildRequest(context.raw(), context.input().schema(), context.modelSpace()));
        return new SecondaryAgentResult<>(dto, context.raw());
    }

    private ChatRequest<T> buildRequest(String input, String schema, ModelSpace modelSpace) {
        return ChatRequest.usingData(schema, modelSpace)
            .withSystemPrompt(buildSystemPrompt(input))
            .withThinkingLevel(thinkingLevel)
            .withModelName(modelName)
            .ask(input, responseType);
    }

    private String buildSystemPrompt(String input) {
        return promptRenderer.render(promptTemplate, Map.of(
            DTO_TYPE_PLACEHOLDER, responseType.getSimpleName(),
            RAW_OUTPUT_PLACEHOLDER, input
        ));
    }
}
