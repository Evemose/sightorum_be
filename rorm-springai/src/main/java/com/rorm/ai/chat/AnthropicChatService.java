package com.rorm.ai.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.anthropic.api.AnthropicApi.*;
import org.springframework.ai.anthropic.api.AnthropicApi.ContentBlock.Source;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Primary
@Component
public class AnthropicChatService implements AiChatService {

    private static final int MAX_TOOL_ROUNDS = 20;
    private static final int DEFAULT_MAX_TOKENS = 64000;
    private static final String DEFAULT_MODEL = "claude-opus-4-6";
    private static final ContentBlock EMPTY = new ContentBlock(null, (Source) null);
    private final AnthropicApi api;
    private final ChatRequestPreprocessor preprocessor;
    private final ObjectMapper objectMapper;

    public AnthropicChatService(
        AnthropicApi api,
        ChatRequestPreprocessor preprocessor,
        ObjectMapper objectMapper
    ) {
        this.api = api;
        this.preprocessor = preprocessor;
        this.objectMapper = objectMapper;
    }

    private static ContentBlock toolResult(String toolUseId, String content) {
        return ContentBlock.from(EMPTY).type(ContentBlock.Type.TOOL_RESULT).toolUseId(toolUseId).content(content).build();
    }

    private static AnthropicMessage userMessage(String text) {
        return new AnthropicMessage(List.of(textBlock(text)), Role.USER);
    }

    private static ContentBlock textBlock(String text) {
        return ContentBlock.from(EMPTY).type(ContentBlock.Type.TEXT).text(text).build();
    }

    private static String errorJson(String toolName, Exception e) {
        var msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        return "{\"error\":true,\"tool\":\"%s\",\"message\":\"%s\"}".formatted(
            toolName.replace("\"", "\\\""), msg.replace("\"", "\\\"")
        );
    }

    @Override
    public <T> T call(ChatRequest<T> request) {
        var session = newSession(request);

        for (var round = 0; round < MAX_TOOL_ROUNDS; round++) {
            var response = api.chatCompletionEntity(session.buildApiRequest(false)).getBody();
            session.addAssistantMessage(response.content());

            if (!"tool_use".equals(response.stopReason())) {
                return convertResponse(extractText(response.content()), request.responseType());
            }
            session.executeAndAppendToolResults(response.content());
        }
        throw new IllegalStateException("Tool call loop exceeded " + MAX_TOOL_ROUNDS + " rounds");
    }

    private ConversationSession newSession(ChatRequest<?> request) {
        var callbacks = preprocessor.resolveToolCallbacks(request);
        return new ConversationSession(
            indexByName(callbacks),
            toAnthropicTools(callbacks),
            preprocessor.buildToolContext(request),
            preprocessor.resolveSystemPrompt(request),
            request.modelName() != null ? request.modelName() : DEFAULT_MODEL,
            resolveThinking(request.thinkingLevel()),
            request.userPrompt(),
            request.toolGroups().contains(ToolGroup.WEB_ACCESS)
        );
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

    private String extractText(List<ContentBlock> content) {
        return content.stream()
            .filter(b -> b.type() == ContentBlock.Type.TEXT)
            .map(ContentBlock::text)
            .filter(Objects::nonNull)
            .collect(Collectors.joining());
    }

    private Map<String, ToolCallback> indexByName(List<ToolCallback> callbacks) {
        return callbacks.stream()
            .collect(Collectors.toMap(cb -> cb.getToolDefinition().name(), Function.identity()));
    }

    private List<Tool> toAnthropicTools(List<ToolCallback> callbacks) {
        return callbacks.stream().map(this::toAnthropicTool).toList();
    }

    private ChatCompletionRequest.ThinkingConfig resolveThinking(ThinkingLevel level) {
        return switch (level) {
            case NONE -> null;
            case MEDIUM -> new ChatCompletionRequest.ThinkingConfig(ThinkingType.ENABLED, 5000);
            case HIGH -> new ChatCompletionRequest.ThinkingConfig(ThinkingType.ENABLED, 16000);
        };
    }

    private Tool toAnthropicTool(ToolCallback cb) {
        var def = cb.getToolDefinition();
        Map<String, Object> schema;
        try {
            schema = objectMapper.readValue(def.inputSchema(), new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse tool schema for {}: {}", def.name(), e.getMessage());
            schema = Map.of("type", "object", "properties", Map.of());
        }
        return new Tool(null, def.name(), def.description(), schema, null);
    }

    @Override
    public Flux<String> stream(ChatRequest<?> request) {
        return streamRound(newSession(request), 0);
    }

    private Flux<String> streamRound(ConversationSession session, int round) {
        if (round >= MAX_TOOL_ROUNDS) {
            return Flux.error(new IllegalStateException("Tool call loop exceeded " + MAX_TOOL_ROUNDS + " rounds"));
        }

        var state = new StreamRoundState();

        return api.chatCompletionStream(session.buildApiRequest(true))
            .onErrorContinue((err, _) -> {
                if (err instanceof RuntimeException re
                    && re.getCause() instanceof InvalidTypeIdException) {
                    log.debug("Skipping unknown content block type: {}", err.getMessage());
                } else {
                    throw Exceptions.propagate(err);
                }
            })
            .flatMapIterable(state::processChunk)
            .concatWith(Flux.defer(() -> {
                if (!state.requiresToolExecution()) {
                    return Flux.empty();
                }
                session.addAssistantMessage(state.buildAssistantContent());
                session.executeAndAppendToolResults(state.toolUseBlocks());
                return streamRound(session, round + 1);
            }));
    }

    private static final class StreamRoundState {

        private final List<ContentBlock> toolUseBlocks = new ArrayList<>();
        private final StringBuilder text = new StringBuilder();
        private final AtomicReference<String> stopReason = new AtomicReference<>();
        private boolean thinkingStarted = false;

        List<String> processChunk(ChatCompletionResponse chunk) {
            if (chunk.stopReason() != null) {
                stopReason.set(chunk.stopReason());
            }
            if (chunk.content() == null) {
                return List.of();
            }

            var emissions = new ArrayList<String>();
            for (var block : chunk.content()) {
                var emission = processBlock(block);
                if (emission != null) {
                    emissions.add(emission);
                }
            }
            return emissions;
        }

        private String processBlock(ContentBlock block) {
            if (block.type() == null) {
                return null;
            }
            return switch (block.type()) {
                case TEXT_DELTA -> {
                    thinkingStarted = false;
                    if (block.text() != null) {
                        text.append(block.text());
                    }
                    yield block.text();
                }
                case THINKING_DELTA -> {
                    if (block.thinking() != null) {
                        var res = block.thinking();
                        if (!thinkingStarted) {
                            thinkingStarted = true;
                            res = "[thinking] " + res;
                        }
                        yield res;
                    }
                    yield null;
                }
                case TOOL_USE -> {
                    thinkingStarted = false;
                    toolUseBlocks.add(block);
                    yield "[tool_call] " + block.name();
                }
                default -> null;
            };
        }

        boolean requiresToolExecution() {
            return "tool_use".equals(stopReason.get());
        }

        List<ContentBlock> buildAssistantContent() {
            var content = new ArrayList<ContentBlock>();
            if (!text.isEmpty()) {
                content.add(textBlock(text.toString()));
            }
            content.addAll(toolUseBlocks);
            return content;
        }

        List<ContentBlock> toolUseBlocks() {
            return toolUseBlocks;
        }
    }

    private final class ConversationSession {

        private final List<AnthropicMessage> messages = new ArrayList<>();
        private final Map<String, ToolCallback> callbackMap;
        private final List<Tool> tools;
        private final ToolContext toolContext;
        private final String systemPrompt;
        private final String model;
        private final ChatCompletionRequest.ThinkingConfig thinking; // null when NONE

        private ConversationSession(
            Map<String, ToolCallback> callbackMap,
            List<Tool> tools,
            ToolContext toolContext,
            String systemPrompt,
            String model,
            ChatCompletionRequest.ThinkingConfig thinking,
            String userPrompt,
            boolean hasWebAccess
        ) {
            this.callbackMap = callbackMap;
            this.tools = new ArrayList<>(tools);
            if (hasWebAccess) {
                this.tools.add(new AnthropicApi.Tool(
                    "web_search_20260209", "web_search",
                    null, null, null
                ));
            }
            this.toolContext = toolContext;
            this.systemPrompt = systemPrompt;
            this.model = model;
            this.thinking = thinking;
            messages.add(userMessage(userPrompt));
        }

        ChatCompletionRequest buildApiRequest(boolean stream) {
            var builder = ChatCompletionRequest.builder()
                .model(model)
                .messages(messages)
                .system(systemPrompt)
                .maxTokens(thinking != null ? DEFAULT_MAX_TOKENS + thinking.budgetTokens() : DEFAULT_MAX_TOKENS)
                .stream(stream);

            if (thinking != null) {
                builder.thinking(thinking);
            } else {
                builder.temperature(0.7);
            }

            if (!tools.isEmpty()) {
                builder
                    .tools(tools)
                    .toolChoice(new ToolChoiceAuto("auto", null));
            }

            return builder.build();
        }

        void addAssistantMessage(List<ContentBlock> content) {
            messages.add(new AnthropicMessage(content, Role.ASSISTANT));
        }

        void executeAndAppendToolResults(List<ContentBlock> content) {
            var results = content.stream()
                .filter(b -> b.type() == ContentBlock.Type.TOOL_USE)
                .map(this::executeToolCall)
                .toList();
            messages.add(new AnthropicMessage(results, Role.USER));
        }

        private ContentBlock executeToolCall(ContentBlock block) {
            var name = block.name();
            var id = block.id();
            log.info("Executing tool: {} ({})", name, id);

            var callback = callbackMap.get(name);
            if (callback == null) {
                log.error("Unknown tool: {}", name);
                return toolResult(id, "{\"error\":\"Unknown tool: " + name + "\"}");
            }

            try {
                var inputJson = objectMapper.writeValueAsString(block.input());
                var result = callback.call(inputJson, toolContext);
                log.debug("Tool {} returned {} chars", name, result.length());
                return toolResult(id, result);
            } catch (Exception e) {
                log.error("Tool {} failed: {}", name, e.getMessage(), e);
                return toolResult(id, errorJson(name, e));
            }
        }
    }
}
