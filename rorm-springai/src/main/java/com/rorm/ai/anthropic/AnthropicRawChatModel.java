package com.rorm.ai.anthropic;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.helpers.MessageAccumulator;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ServerToolUseBlock;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.observation.ChatModelObservationConvention;
import org.springframework.ai.chat.observation.ChatModelObservationDocumentation;
import org.springframework.ai.chat.observation.DefaultChatModelObservationConvention;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.retry.support.RetryTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Spring AI {@link ChatModel} backed by the raw Anthropic Java SDK.
 *
 * <p>Does NOT auto-loop on tool calls — returns them for the caller to handle.
 * This enables async/checkpointed tools with full control over persistence boundaries.
 */
@Slf4j
public class AnthropicRawChatModel implements ChatModel {

    private static final String PROVIDER = "anthropic";
    private static final ChatModelObservationConvention DEFAULT_OBSERVATION_CONVENTION =
        new DefaultChatModelObservationConvention();

    private final AnthropicClient client;
    private final AnthropicParamsBuilder paramsBuilder;
    private final ObjectMapper objectMapper;
    private final RetryTemplate retryTemplate;
    private final ObservationRegistry observationRegistry;
    private final ChatModelObservationConvention observationConvention = DEFAULT_OBSERVATION_CONVENTION;

    public AnthropicRawChatModel(
        AnthropicClient client,
        ObjectMapper objectMapper,
        @Nullable RetryTemplate retryTemplate,
        @Nullable ObservationRegistry observationRegistry
    ) {
        this.client = client;
        this.paramsBuilder = new AnthropicParamsBuilder(objectMapper);
        this.objectMapper = objectMapper;
        this.retryTemplate = retryTemplate != null ? retryTemplate : RetryTemplate.builder().maxAttempts(1).build();
        this.observationRegistry = observationRegistry != null ? observationRegistry : ObservationRegistry.NOOP;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        var observationContext = ChatModelObservationContext.builder()
            .prompt(prompt)
            .provider(PROVIDER)
            .build();

        return Objects.requireNonNull(ChatModelObservationDocumentation.CHAT_MODEL_OPERATION
            .observation(this.observationConvention, DEFAULT_OBSERVATION_CONVENTION,
                () -> observationContext, this.observationRegistry)
            .observe(() -> retryTemplate.execute(_ -> {
                var params = paramsBuilder.build(prompt);
                var response = client.messages().create(params);
                var chatResponse = toChatResponse(response);
                observationContext.setResponse(chatResponse);
                return chatResponse;
            })));
    }

    private ChatResponse toChatResponse(com.anthropic.models.messages.Message message) {
        var text = message.content().stream()
            .filter(ContentBlock::isText)
            .map(b -> b.asText().text())
            .collect(Collectors.joining());

        var toolCalls = message.content().stream()
            .filter(ContentBlock::isToolUse)
            .map(b -> {
                var tu = b.asToolUse();
                return new AssistantMessage.ToolCall(tu.id(), "function", tu.name(), toJson(tu._input()));
            })
            .toList();

        var assistantMessage = AssistantMessage.builder()
            .content(text)
            .toolCalls(toolCalls)
            .build();

        var generation = new Generation(assistantMessage, ChatGenerationMetadata.NULL);

        var sdkUsage = message.usage();
        var usage = new Usage() {
            @Override
            public Integer getPromptTokens() {
                return (int) sdkUsage.inputTokens();
            }

            @Override
            public Integer getCompletionTokens() {
                return (int) sdkUsage.outputTokens();
            }

            @Override
            public Object getNativeUsage() {
                return sdkUsage;
            }
        };

        return new ChatResponse(
            List.of(generation),
            ChatResponseMetadata.builder().model(message.model().toString()).usage(usage).build()
        );
    }

    private String toJson(JsonValue value) {
        try {
            return objectMapper.writeValueAsString(value.convert(Object.class));
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.<ChatResponse>create(sink ->
            retryTemplate.execute(_ -> {
                doStream(prompt, sink);
                return null;
            })
        ).subscribeOn(Schedulers.boundedElastic());
    }

    private void doStream(Prompt prompt, FluxSink<ChatResponse> sink) {
        var params = paramsBuilder.build(prompt);
        var accumulator = MessageAccumulator.create();
        var thinkingStarted = new boolean[]{false};

        try (var stream = client.messages().createStreaming(params)) {
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
                        sink.next(textChunk(delta.asText().text()));
                    } else if (delta.isThinking()) {
                        var text = delta.asThinking().thinking();
                        if (!thinkingStarted[0]) {
                            thinkingStarted[0] = true;
                            text = "[thinking] " + text;
                        }
                        sink.next(textChunk(text));
                    }
                });
                event.contentBlockStart().ifPresent(startEvent -> {
                    var block = startEvent.contentBlock();
                    if (block.isToolUse()) {
                        thinkingStarted[0] = false;
                        sink.next(textChunk("[tool_call] " + block.asToolUse().name()));
                    } else if (block.isServerToolUse()) {
                        thinkingStarted[0] = false;
                        sink.next(textChunk(formatServerToolStart(block.asServerToolUse())));
                    }
                });
            });
        }

        sink.next(toChatResponse(accumulator.message()));
        sink.complete();
    }

    private ChatResponse textChunk(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    @SuppressWarnings("unchecked")
    private static String formatServerToolStart(ServerToolUseBlock serverTool) {
        var name = serverTool.name().toString();
        var map = (Map<String, JsonValue>) serverTool._input().asObject().orElse(null);
        if (map != null && map.containsKey("query")) {
            var query = map.get("query").asString().orElse(null);
            if (query != null) {
                return "[" + name + "] " + query;
            }
        }
        return "[" + name + "]";
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return AnthropicChatOptions.builder().build();
    }
}
