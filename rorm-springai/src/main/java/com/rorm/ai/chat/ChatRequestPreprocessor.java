package com.rorm.ai.chat;

import com.rorm.ai.RormToolContext;
import com.rorm.ai.prompt.AgentPromptBuilder;
import com.rorm.ai.prompt.PromptPlaceholders;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/**
 * Shared preprocessing for all {@link AiChatService} implementations.
 * Resolves system prompts (with common placeholders), tool callbacks, and tool context.
 */
@Component
@RequiredArgsConstructor
public class ChatRequestPreprocessor {

    private final AgentPromptBuilder promptBuilder;
    private final PromptPlaceholders placeholders;
    private final ToolGroupResolver toolGroupResolver;

    /**
     * Resolves the system prompt for a request. If the request carries a custom prompt,
     * common placeholders ({{METAMODEL}}, {{QUERY_STRUCTURE}}) are still resolved.
     * Otherwise the default agent prompt is generated.
     */
    public String resolveSystemPrompt(ChatRequest<?> request) {
        if (request.systemPrompt() != null) {
            return placeholders.resolve(request.systemPrompt(), request.modelSpace());
        }
        return promptBuilder.buildSystemMessage(request.modelSpace());
    }

    public List<ToolCallback> resolveToolCallbacks(ChatRequest<?> request) {
        var toolObjects = new ArrayList<>(toolGroupResolver.resolve(request.toolGroups()));
        toolObjects.addAll(request.additionalTools());
        if (toolObjects.isEmpty()) {
            return List.of();
        }
        return Arrays.asList(ToolCallbacks.from(toolObjects.toArray()));
    }

    public ToolContext buildToolContext(ChatRequest<?> request) {
        var context = new RormToolContext(request.modelSpace(), request.schema());
        var contextMap = new HashMap<>(context.toMap());
        contextMap.putAll(request.toolContextEntries());
        return new ToolContext(contextMap);
    }
}
