package com.rorm.ai.anthropic;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.*;
import com.anthropic.models.messages.MessageCreateParams.Builder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.ai.anthropic.AnthropicChatOptions.CacheTTL;
import com.rorm.ai.chat.ServerToolMessage;
import com.rorm.ai.chat.ThinkingLevel;
import com.rorm.ai.chat.ThinkingMessage;
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
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class AnthropicParamsBuilder {

    private static final long DEFAULT_MAX_TOKENS = 64_000L;
    private static final String DEFAULT_MODEL = "claude-sonnet-4-6";

    private final ObjectMapper objectMapper;

    public MessageCreateParams.Builder toBuilder(Prompt prompt, CacheTTL cacheTTL) {
        var options = prompt.getOptions();
        var model = options != null && options.getModel() != null ? options.getModel() : DEFAULT_MODEL;
        var maxTokens = options != null && options.getMaxTokens() != null
            ? options.getMaxTokens().longValue() : DEFAULT_MAX_TOKENS;

        var builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(maxTokens);

        configureThinking(builder, options);
        configureSystemPrompt(builder, prompt, cacheTTL);
        configureTools(builder, options, cacheTTL);
        addMessages(builder, prompt, cacheTTL);

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

    private void configureSystemPrompt(MessageCreateParams.Builder builder, Prompt prompt, CacheTTL cacheTTL) {
        prompt.getInstructions().stream()
            .filter(SystemMessage.class::isInstance)
            .findFirst()
            .ifPresent(sys -> {
                var textBuilder = TextBlockParam.builder().text(sys.getText());
                var cc = cacheControl(cacheTTL);
                if (cc != null) {
                    textBuilder.cacheControl(cc);
                }
                builder.systemOfTextBlockParams(List.of(textBuilder.build()));
            });
    }

    private void configureTools(MessageCreateParams.Builder builder, ChatOptions options, CacheTTL cacheTTL) {
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

        var cc = cacheControl(cacheTTL);
        var tools = callbacks.stream().map(this::toSdkTool)
            .sorted(java.util.Comparator.comparing(Tool::name))
            .toList();
        for (int i = 0; i < tools.size(); i++) {
            var tool = tools.get(i);
            if (cc != null && i == tools.size() - 1 && !webAccess) {
                tool = tool.toBuilder().cacheControl(cc).build();
            }
            builder.addTool(tool);
        }
        if (webAccess) {
            var webToolBuilder = WebSearchTool20260209.builder();
            if (cc != null) {
                webToolBuilder.cacheControl(cc);
            }
            builder.addTool(webToolBuilder.build());
        }
        builder.toolChoice(ToolChoiceAuto.builder().build());
    }

    private void addMessages(MessageCreateParams.Builder builder, Prompt prompt, CacheTTL cacheTTL) {
        var instructions = prompt.getInstructions();
        var cc = cacheControl(cacheTTL);
        var firstUserMessageSeen = false;
        for (var i = 0; i < instructions.size(); i++) {
            var message = instructions.get(i);
            switch (message) {
                case UserMessage user -> {
                    var textBuilder = TextBlockParam.builder().text(user.getText());
                    if (cc != null) {
                        if (!firstUserMessageSeen) {
                            firstUserMessageSeen = true;
                            textBuilder.cacheControl(cc);
                        } else if (i == instructions.size() - 1) {
                            textBuilder.cacheControl(cc);
                        }
                    }
                    builder.addUserMessageOfBlockParams(List.of(
                        ContentBlockParam.ofText(textBuilder.build())
                    ));
                }
                case AssistantMessage assistant -> addAssistantMessage(builder, assistant);
                case ToolResponseMessage toolResp -> addToolResponses(builder, toolResp);
                case ThinkingMessage thinking -> addThinkingMessage(builder, thinking);
                case SystemMessage _, ServerToolMessage _ -> {
                }
                default -> log.debug("Ignoring message type: {}", message.getClass().getSimpleName());
            }
        }
    }

    private static CacheControlEphemeral cacheControl(CacheTTL cacheTTL) {
        return switch (cacheTTL) {
            case NONE -> null;
            case SHORT -> CacheControlEphemeral.builder().ttl(CacheControlEphemeral.Ttl.TTL_5M).build();
            case LONG -> CacheControlEphemeral.builder().ttl(CacheControlEphemeral.Ttl.TTL_1H).build();
        };
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

    private void addThinkingMessage(Builder builder, ThinkingMessage thinking) {
        builder.addMessage(MessageParam.builder()
            .role(MessageParam.Role.ASSISTANT)
            .content(thinking.text())
            .build()
        );
    }

    private JsonValue parseJsonValue(String json) {
        try {
            return JsonValue.from(objectMapper.readValue(json, Object.class));
        } catch (JsonProcessingException e) {
            return JsonValue.from(Map.of());
        }
    }
}
