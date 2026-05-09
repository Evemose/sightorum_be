package com.rorm.ai.anthropic;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.*;
import com.anthropic.models.messages.MessageCreateParams.Builder;
import com.anthropic.models.messages.OutputConfig.Effort;
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
import java.util.Optional;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class AnthropicParamsBuilder {

    private static final long DEFAULT_MAX_TOKENS = 64_000L;
    static final String DEFAULT_MODEL = "claude-sonnet-4-6";
    static final int MAX_CACHE_BREAKPOINTS = 4;
    private static final Pattern INLINE_CACHE_MARKER = Pattern.compile("<--CACHE\\[(5m|1h)]-->");

    private final ObjectMapper objectMapper;

    private static CacheControlEphemeral claim(CacheBreakpointBudget budget, CacheTTL cacheTTL) {
        return switch (cacheTTL) {
            case NONE -> null;
            case SHORT -> budget.tryClaim(CacheControlEphemeral.Ttl.TTL_5M);
            case LONG -> budget.tryClaim(CacheControlEphemeral.Ttl.TTL_1H);
        };
    }

    private static List<TextBlockParam> splitWithInlineMarkers(
        String text, CacheTTL trailingCacheTTL, CacheBreakpointBudget budget
    ) {
        var matcher = INLINE_CACHE_MARKER.matcher(text);
        var blocks = new ArrayList<TextBlockParam>();
        int last = 0;
        while (matcher.find()) {
            var chunk = text.substring(last, matcher.start());
            last = matcher.end();
            if (chunk.isEmpty()) {
                continue;
            }
            var ttl = "5m".equals(matcher.group(1))
                ? CacheControlEphemeral.Ttl.TTL_5M
                : CacheControlEphemeral.Ttl.TTL_1H;
            var b = TextBlockParam.builder().text(chunk);
            var cc = budget.tryClaim(ttl);
            if (cc != null) {
                b.cacheControl(cc);
            }
            blocks.add(b.build());
        }
        var tail = text.substring(last);
        if (!tail.isEmpty()) {
            var tailBuilder = TextBlockParam.builder().text(tail);
            var cc = claim(budget, trailingCacheTTL);
            if (cc != null) {
                tailBuilder.cacheControl(cc);
            }
            blocks.add(tailBuilder.build());
        }
        if (blocks.isEmpty()) {
            blocks.add(TextBlockParam.builder().text(text).build());
        }
        return blocks;
    }

    public MessageCreateParams.Builder toBuilder(Prompt prompt, CacheTTL cacheTTL) {
        return toBuilder(prompt, cacheTTL, new CacheBreakpointBudget());
    }

    private void configureOutputConfig(MessageCreateParams.Builder builder, ChatOptions options) {
        var ao = options instanceof AnthropicChatOptions a ? a : null;
        var hasThinking = ao != null
            && ao.getThinkingLevel() != null
                          && ao.getThinkingLevel() != ThinkingLevel.NONE;
        var hasSchema = ao != null && ao.getResponseSchema() != null;

        if (hasThinking) {
            builder.thinking(ThinkingConfigAdaptive.builder()
                .putAdditionalProperty("display", JsonValue.from("summarized"))
                .build());
        }

        if (hasThinking || hasSchema) {
            var outputBuilder = OutputConfig.builder();
            if (hasThinking) {
                outputBuilder.effort(switch (ao.getThinkingLevel()) {
                    case NONE -> throw new IllegalStateException();
                    case MEDIUM -> Effort.MEDIUM;
                    case HIGH -> Optional.ofNullable(options.getModel())
                        .filter(m -> m.equals("claude-opus-4-6"))
                        .map(_ -> Effort.MAX)
                        .orElse(Effort.HIGH);
                });
            }
            if (hasSchema) {
                outputBuilder.format(toJsonOutputFormat(ao.getResponseSchema()));
            }
            builder.outputConfig(outputBuilder.build());
        }

        if (!hasThinking) {
            var temp = options != null && options.getTemperature() != null
                ? options.getTemperature() : 0.7;
            builder.temperature(temp);
        }
    }

    private JsonOutputFormat toJsonOutputFormat(Map<String, Object> schema) {
        var schemaBuilder = JsonOutputFormat.Schema.builder();
        for (var entry : schema.entrySet()) {
            schemaBuilder.putAdditionalProperty(entry.getKey(), JsonValue.from(entry.getValue()));
        }
        return JsonOutputFormat.builder().schema(schemaBuilder.build()).build();
    }

    public MessageCreateParams.Builder toBuilder(Prompt prompt, CacheTTL cacheTTL, CacheBreakpointBudget budget) {
        var options = prompt.getOptions();
        var model = options != null && options.getModel() != null ? options.getModel() : DEFAULT_MODEL;
        var maxTokens = options != null && options.getMaxTokens() != null
            ? options.getMaxTokens().longValue() : DEFAULT_MAX_TOKENS;

        var builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(maxTokens);

        configureOutputConfig(builder, options);
        configureSystemPrompt(builder, prompt, cacheTTL, budget);
        configureTools(builder, options, cacheTTL, budget);
        addMessages(builder, prompt, cacheTTL, budget);

        return builder;
    }

    private void configureSystemPrompt(
        MessageCreateParams.Builder builder, Prompt prompt, CacheTTL cacheTTL, CacheBreakpointBudget budget
    ) {
        prompt.getInstructions().stream()
            .filter(SystemMessage.class::isInstance)
            .findFirst()
            .ifPresent(sys -> builder.systemOfTextBlockParams(
                splitWithInlineMarkers(sys.getText(), cacheTTL, budget)));
    }

    private void configureTools(
        MessageCreateParams.Builder builder, ChatOptions options, CacheTTL cacheTTL, CacheBreakpointBudget budget
    ) {
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

        var tools = callbacks.stream().map(this::toSdkTool)
            .sorted(java.util.Comparator.comparing(Tool::name))
            .toList();
        for (int i = 0; i < tools.size(); i++) {
            var tool = tools.get(i);
            if (i == tools.size() - 1 && !webAccess) {
                var cc = claim(budget, cacheTTL);
                if (cc != null) {
                    tool = tool.toBuilder().cacheControl(cc).build();
                }
            }
            builder.addTool(tool);
        }
        if (webAccess) {
            var webToolBuilder = WebSearchTool20260209.builder();
            var cc = claim(budget, cacheTTL);
            if (cc != null) {
                webToolBuilder.cacheControl(cc);
            }
            builder.addTool(webToolBuilder.build());
        }
        builder.toolChoice(ToolChoiceAuto.builder().build());
    }

    private void addMessages(
        MessageCreateParams.Builder builder, Prompt prompt, CacheTTL cacheTTL, CacheBreakpointBudget budget
    ) {
        var instructions = prompt.getInstructions();
        var firstUserMessageSeen = false;
        for (var message : instructions) {
            switch (message) {
                case UserMessage user -> {
                    var trailingTtl = !firstUserMessageSeen ? cacheTTL : CacheTTL.NONE;
                    firstUserMessageSeen = true;
                    var blocks = splitWithInlineMarkers(user.getText(), trailingTtl, budget).stream()
                        .map(ContentBlockParam::ofText)
                        .toList();
                    builder.addUserMessageOfBlockParams(blocks);
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

    public static final class CacheBreakpointBudget {
        private int remaining = MAX_CACHE_BREAKPOINTS;

        public CacheControlEphemeral tryClaim(CacheControlEphemeral.Ttl ttl) {
            if (remaining <= 0) {
                return null;
            }
            remaining--;
            return CacheControlEphemeral.builder().ttl(ttl).build();
        }

        public int remaining() {
            return remaining;
        }
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
