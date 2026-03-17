package com.rorm.ai.anthropic;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.ai.chat.ThinkingLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
public class AnthropicParamsBuilder {

    private static final long DEFAULT_MAX_TOKENS = 64_000L;
    private static final String DEFAULT_MODEL = "claude-sonnet-4-6";

    private final ObjectMapper objectMapper;

    public MessageCreateParams build(Prompt prompt) {
        return toBuilder(prompt).build();
    }

    public MessageCreateParams.Builder toBuilder(Prompt prompt) {
        var options = prompt.getOptions();
        var model = options != null && options.getModel() != null ? options.getModel() : DEFAULT_MODEL;
        var maxTokens = options != null && options.getMaxTokens() != null
            ? options.getMaxTokens().longValue() : DEFAULT_MAX_TOKENS;

        var builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(maxTokens);

        configureThinking(builder, options);
        configureSystemPrompt(builder, prompt);
        configureTools(builder, options);
        addMessages(builder, prompt);

        return builder;
    }

    private void configureThinking(MessageCreateParams.Builder builder, ChatOptions options) {
        if (options instanceof AnthropicChatOptions ao
            && ao.getThinkingLevel() != null
            && ao.getThinkingLevel() != ThinkingLevel.NONE) {
            builder.thinking(ThinkingConfigAdaptive.builder().build());
            return;
        }
        var temp = options != null && options.getTemperature() != null ? options.getTemperature() : 0.7;
        builder.temperature(temp);
    }

    private void configureSystemPrompt(MessageCreateParams.Builder builder, Prompt prompt) {
        prompt.getInstructions().stream()
            .filter(SystemMessage.class::isInstance)
            .findFirst()
            .ifPresent(sys -> builder.systemOfTextBlockParams(List.of(
                TextBlockParam.builder().text(sys.getText()).cacheControl(longCache()).build()
            )));
    }

    private void configureTools(MessageCreateParams.Builder builder, ChatOptions options) {
        List<ToolCallback> callbacks = List.of();
        boolean webAccess = false;

        if (options instanceof ToolCallingChatOptions toolOptions) {
            callbacks = toolOptions.getToolCallbacks();
        }
        if (options instanceof AnthropicChatOptions ao) {
            webAccess = ao.isWebAccess();
        }

        if (callbacks.isEmpty() && !webAccess) {
            return;
        }

        var tools = callbacks.stream().map(this::toSdkTool).toList();
        for (int i = 0; i < tools.size(); i++) {
            var tool = tools.get(i);
            if (i == tools.size() - 1 && !webAccess) {
                tool = tool.toBuilder().cacheControl(longCache()).build();
            }
            builder.addTool(tool);
        }
        if (webAccess) {
            builder.addTool(WebSearchTool20260209.builder()
                .cacheControl(longCache()).build());
        }
        builder.toolChoice(ToolChoiceAuto.builder().build());
    }

    private void addMessages(MessageCreateParams.Builder builder, Prompt prompt) {
        for (var message : prompt.getInstructions()) {
            switch (message) {
                case UserMessage user -> builder.addUserMessageOfBlockParams(List.of(
                    ContentBlockParam.ofText(TextBlockParam.builder().text(user.getText()).build())
                ));
                case AssistantMessage assistant -> addAssistantMessage(builder, assistant);
                case ToolResponseMessage toolResp -> addToolResponses(builder, toolResp);
                case SystemMessage _ -> {
                }
                default -> log.debug("Ignoring message type: {}", message.getClass().getSimpleName());
            }
        }
    }

    private static CacheControlEphemeral longCache() {
        return CacheControlEphemeral.builder().ttl(CacheControlEphemeral.Ttl.TTL_1H).build();
    }

    @SuppressWarnings("unchecked")
    private Tool toSdkTool(ToolCallback cb) {
        var def = cb.getToolDefinition();
        Map<String, Object> schema;
        try {
            schema = objectMapper.readValue(def.inputSchema(), new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse tool schema for {}: {}", def.name(), e.getMessage());
            schema = Map.of("type", "object", "properties", Map.of());
        }
        return Tool.builder()
            .name(def.name())
            .description(def.description())
            .inputSchema(Tool.InputSchema.builder()
                .properties(JsonValue.from(schema.get("properties")))
                .putAdditionalProperty("required",
                    JsonValue.from(schema.getOrDefault("required", List.of())))
                .build())
            .build();
    }

    private void addAssistantMessage(MessageCreateParams.Builder builder, AssistantMessage assistant) {
        var contentBlocks = new ArrayList<ContentBlockParam>();
        if (assistant.getText() != null && !assistant.getText().isBlank()) {
            contentBlocks.add(ContentBlockParam.ofText(
                TextBlockParam.builder().text(assistant.getText()).build()));
        }
        for (var tc : assistant.getToolCalls()) {
            contentBlocks.add(ContentBlockParam.ofToolUse(
                ToolUseBlockParam.builder()
                    .id(tc.id())
                    .name(tc.name())
                    .input(ToolUseBlockParam.Input.builder()
                        .putAdditionalProperty("_raw", parseJsonValue(tc.arguments()))
                        .build())
                    .build()));
        }
        if (!contentBlocks.isEmpty()) {
            builder.addMessage(MessageParam.builder()
                .role(MessageParam.Role.ASSISTANT)
                .contentOfBlockParams(contentBlocks)
                .build());
        }
    }

    private void addToolResponses(MessageCreateParams.Builder builder, ToolResponseMessage toolResp) {
        var blocks = toolResp.getResponses().stream()
            .map(r -> ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                .toolUseId(r.id())
                .content(r.responseData())
                .build()))
            .toList();
        builder.addUserMessageOfBlockParams(blocks);
    }

    private JsonValue parseJsonValue(String json) {
        try {
            return JsonValue.from(objectMapper.readValue(json, Object.class));
        } catch (JsonProcessingException e) {
            return JsonValue.from(Map.of());
        }
    }
}
