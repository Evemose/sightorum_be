package com.rorm.ai.chat;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.helpers.MessageAccumulator;
import com.anthropic.models.messages.*;
import com.anthropic.models.messages.MessageCreateParams.Builder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Primary
@Component
public class AnthropicChatService implements AiChatService {

    private static final int MAX_TOOL_ROUNDS = 20;
    private static final long DEFAULT_MAX_TOKENS = 64_000L;
    private static final String DEFAULT_MODEL = "claude-opus-4-6";

    private final AnthropicClient client;
    private final ChatRequestPreprocessor preprocessor;
    private final ObjectMapper objectMapper;

    public AnthropicChatService(
        AnthropicClient client,
        ChatRequestPreprocessor preprocessor,
        ObjectMapper objectMapper
    ) {
        this.client = client;
        this.preprocessor = preprocessor;
        this.objectMapper = objectMapper;
    }

    @Override
    public <T> T call(ChatRequest<T> request) {
        var converter = converterFor(request.responseType());
        var session = newSession(request, converter);
        var paramsBuilder = session.newParamsBuilder();

        for (var round = 0; round < MAX_TOOL_ROUNDS; round++) {
            var response = client.messages().create(paramsBuilder.build());

            if (response.stopReason().orElse(null) != StopReason.TOOL_USE) {
                var text = extractText(response);
                return converter != null ? converter.convert(text) : convertResponse(text, request.responseType());
            }

            paramsBuilder.addMessage(response);
            paramsBuilder.addUserMessageOfBlockParams(session.executeTools(response));
        }
        throw new IllegalStateException("Tool call loop exceeded " + MAX_TOOL_ROUNDS + " rounds");
    }

    private <T> @Nullable BeanOutputConverter<T> converterFor(Class<T> responseType) {
        if (responseType == String.class) {
            return null;
        }
        return new BeanOutputConverter<>(responseType);
    }

    private ConversationSession newSession(ChatRequest<?> request, @Nullable BeanOutputConverter<?> converter) {
        var callbacks = preprocessor.resolveToolCallbacks(request);
        var userPrompt = preprocessor.resolveUserPrompt(request);
        if (converter != null) {
            userPrompt = converter.getFormat() + "\n\n" + userPrompt;
        }
        return new ConversationSession(
            indexByName(callbacks),
            toSdkTools(callbacks),
            preprocessor.buildToolContext(request),
            preprocessor.resolveSystemPrompt(request),
            request.modelName() != null ? request.modelName() : DEFAULT_MODEL,
            resolveThinking(request.thinkingLevel()),
            userPrompt,
            request.toolGroups().contains(ToolGroup.WEB_ACCESS),
            objectMapper
        );
    }

    private String extractText(Message message) {
        return message.content().stream()
            .filter(ContentBlock::isText)
            .map(b -> b.asText().text())
            .collect(Collectors.joining());
    }

    @SuppressWarnings("unchecked")
    private <T> T convertResponse(String text, Class<T> responseType) {
        if (responseType == String.class) {
            return (T) text;
        }
        try {
            return objectMapper.readValue(text, responseType);
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize response to {}: {}", responseType.getSimpleName(), e.getMessage());
            throw new RuntimeException("Failed to parse AI response as " + responseType.getSimpleName(), e);
        }
    }

    private Map<String, ToolCallback> indexByName(List<ToolCallback> callbacks) {
        return callbacks.stream()
            .collect(Collectors.toMap(cb -> cb.getToolDefinition().name(), Function.identity()));
    }

    private List<Tool> toSdkTools(List<ToolCallback> callbacks) {
        return callbacks.stream().map(this::toSdkTool).toList();
    }

    private ThinkingConfigAdaptive resolveThinking(ThinkingLevel level) {
        return switch (level) {
            case NONE -> null;
            case MEDIUM, HIGH -> ThinkingConfigAdaptive.builder().build();
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

    @SuppressWarnings("unchecked")
    private static String formatServerToolStart(String name, JsonValue input) {
        var map = (Map<String, JsonValue>) input.asObject().orElse(null);
        if (map != null && map.containsKey("query")) {
            var query = map.get("query").asString().orElse(null);
            if (query != null) {
                return "[" + name + "] " + query;
            }
        }
        return "[" + name + "]";
    }

    @Override
    public Flux<String> stream(ChatRequest<?> request) {
        var session = newSession(request, null);
        return Flux.<String>create(sink -> streamLoop(sink, session, session.newParamsBuilder(), 0))
            .subscribeOn(Schedulers.boundedElastic());
    }

    private void streamLoop(
        FluxSink<String> sink,
        ConversationSession session,
        MessageCreateParams.Builder paramsBuilder,
        int round
    ) {
        if (round >= MAX_TOOL_ROUNDS) {
            sink.error(new IllegalStateException("Tool call loop exceeded " + MAX_TOOL_ROUNDS + " rounds"));
            return;
        }

        var accumulator = MessageAccumulator.create();
        var thinkingStarted = new boolean[]{false};

        try (var stream = client.messages().createStreaming(paramsBuilder.build())) {
            stream.stream().peek(event -> {
                try {
                    accumulator.accumulate(event);
                } catch (Exception e) {
                    log.debug("Accumulator skipped event: {}", e.getMessage());
                }
            }).forEach(event -> {
                event.contentBlockDelta().ifPresent(deltaEvent -> {
                    var delta = deltaEvent.delta();
                    if (delta.isText()) {
                        thinkingStarted[0] = false;
                        sink.next(delta.asText().text());
                    } else if (delta.isThinking()) {
                        var text = delta.asThinking().thinking();
                        if (!thinkingStarted[0]) {
                            thinkingStarted[0] = true;
                            text = "[thinking] " + text;
                        }
                        sink.next(text);
                    } else if (delta.isCitations()) {
                        var citation = delta.asCitations().citation();
                        citation.webSearchResultLocation().ifPresent(loc ->
                            sink.next("[citation] " + loc.url()
                                      + loc.title().map(t -> " — " + t).orElse(""))
                        );
                    }
                });
                event.contentBlockStart().ifPresent(startEvent -> {
                    var block = startEvent.contentBlock();
                    if (block.isToolUse()) {
                        thinkingStarted[0] = false;
                        sink.next("[tool_call] " + block.asToolUse().name());
                    } else if (block.isServerToolUse()) {
                        thinkingStarted[0] = false;
                        var serverTool = block.asServerToolUse();
                        var name = serverTool.name().toString();
                        sink.next(formatServerToolStart(name, serverTool._input()));
                    } else if (block.isWebSearchToolResult()) {
                        var content = block.asWebSearchToolResult().content();
                        if (content.isResultBlocks()) {
                            var links = content.asResultBlocks().stream()
                                .map(r -> "  " + r.title() + " — " + r.url())
                                .collect(Collectors.joining("\n"));
                            sink.next("[search_results]\n" + links);
                        } else if (content.isError()) {
                            sink.next("[search_error] " + content.asError().errorCode());
                        }
                    }
                });
            });
        }

        Message message = accumulator.message();
        if (message.stopReason().orElse(null) == StopReason.TOOL_USE) {
            paramsBuilder.addMessage(message);
            paramsBuilder.addUserMessageOfBlockParams(session.executeTools(message));
            streamLoop(sink, session, paramsBuilder, round + 1);
        } else {
            sink.complete();
        }
    }

    private record ConversationSession(
        Map<String, ToolCallback> callbackMap,
        List<Tool> tools,
        ToolContext toolContext,
        String systemPrompt,
        String model,
        ThinkingConfigAdaptive thinking,
        String userPrompt,
        boolean hasWebAccess,
        ObjectMapper objectMapper
    ) {

        Builder newParamsBuilder() {
            var longCache = CacheControlEphemeral.builder()
                .ttl(CacheControlEphemeral.Ttl.TTL_1H).build();
            var shortCache = CacheControlEphemeral.builder().build(); // 5m default

            var builder = MessageCreateParams.builder()
                .model(model)
                .systemOfTextBlockParams(List.of(
                    TextBlockParam.builder().text(systemPrompt).cacheControl(longCache).build()
                ))
                .maxTokens(DEFAULT_MAX_TOKENS);

            if (thinking != null) {
                builder.thinking(thinking);
            } else {
                builder.temperature(0.7);
            }

            boolean hasTools = !tools.isEmpty() || hasWebAccess;
            if (hasTools) {
                for (int i = 0; i < tools.size(); i++) {
                    var tool = tools.get(i);
                    if (i == tools.size() - 1 && !hasWebAccess) {
                        tool = tool.toBuilder().cacheControl(longCache).build();
                    }
                    builder.addTool(tool);
                }
                if (hasWebAccess) {
                    builder.addTool(WebSearchTool20260209.builder()
                        .cacheControl(longCache).build());
                }
                builder.toolChoice(ToolChoiceAuto.builder().build());
            }

            builder.addUserMessageOfBlockParams(List.of(
                ContentBlockParam.ofText(TextBlockParam.builder()
                    .text(userPrompt)
                    .cacheControl(shortCache)
                    .build())
            ));

            return builder;
        }

        List<ContentBlockParam> executeTools(Message response) {
            return response.content().stream()
                .filter(ContentBlock::isToolUse)
                .map(block -> executeToolCall(block.asToolUse()))
                .toList();
        }

        private ContentBlockParam executeToolCall(ToolUseBlock block) {
            var name = block.name();
            var id = block.id();
            log.info("Executing tool: {} ({})", name, id);

            var callback = callbackMap.get(name);
            if (callback == null) {
                log.error("Unknown tool: {}", name);
                return ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                    .toolUseId(id)
                    .content("{\"error\":\"Unknown tool: " + name + "\"}")
                    .build());
            }

            try {
                var inputJson = objectMapper.writeValueAsString(block._input());
                var result = callback.call(inputJson, toolContext);
                log.debug("Tool {} returned {} chars", name, result.length());
                return ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                    .toolUseId(id)
                    .content(result)
                    .build());
            } catch (Exception e) {
                log.error("Tool {} failed: {}", name, e.getMessage(), e);
                return ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                    .toolUseId(id)
                    .content(errorJson(name, e))
                    .build());
            }
        }

        private static String errorJson(String toolName, Exception e) {
            var msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return "{\"error\":true,\"tool\":\"%s\",\"message\":\"%s\"}".formatted(
                toolName.replace("\"", "\\\""), msg.replace("\"", "\\\"")
            );
        }
    }
}
