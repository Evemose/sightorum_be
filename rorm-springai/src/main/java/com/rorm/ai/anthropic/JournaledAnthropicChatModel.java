package com.rorm.ai.anthropic;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
import com.anthropic.helpers.MessageAccumulator;
import com.anthropic.models.messages.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.durable.StepJournal;
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
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.observation.ChatModelObservationConvention;
import org.springframework.ai.chat.observation.ChatModelObservationDocumentation;
import org.springframework.ai.chat.observation.DefaultChatModelObservationConvention;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.retry.support.RetryTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
public class JournaledAnthropicChatModel implements ChatModel {

    private static final String PROVIDER = "anthropic";
    private static final int MAX_TOOL_ROUNDS = 20;
    private static final ChatModelObservationConvention DEFAULT_OBSERVATION_CONVENTION =
        new DefaultChatModelObservationConvention();

    private final AnthropicClient client;
    private final AnthropicParamsBuilder paramsBuilder;
    private final RetryTemplate retryTemplate;
    private final ObservationRegistry observationRegistry;
    private final ChatModelObservationConvention observationConvention = DEFAULT_OBSERVATION_CONVENTION;

    public JournaledAnthropicChatModel(
        AnthropicClient client,
        ObjectMapper objectMapper,
        @Nullable RetryTemplate retryTemplate,
        @Nullable ObservationRegistry observationRegistry
    ) {
        this.client = client;
        this.paramsBuilder = new AnthropicParamsBuilder(objectMapper);
        this.retryTemplate = retryTemplate != null ? retryTemplate : RetryTemplate.builder().maxAttempts(1).build();
        this.observationRegistry = observationRegistry != null ? observationRegistry : ObservationRegistry.NOOP;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return withObservation(prompt, observationCtx -> {
            var callbackMap = resolveToolCallbackMap(prompt.getOptions());
            var toolCtx = resolveToolContext(prompt.getOptions());
            var journal = resolveJournal(prompt.getOptions());
            var builder = paramsBuilder.toBuilder(prompt);

            for (var round = 0; round <= MAX_TOOL_ROUNDS; round++) {
                var response = journal.run("llm-" + round, Message.class,
                    () -> client.messages().create(builder.build()));
                if (!hasToolCalls(response, callbackMap)) {
                    var result = toChatResponse(response);
                    observationCtx.setResponse(result);
                    return result;
                }
                advanceWithToolResults(builder, response, callbackMap, toolCtx, journal);
            }
            throw toolLoopExceeded();
        });
    }

    private ChatResponse withObservation(Prompt prompt, Function<ChatModelObservationContext, ChatResponse> body) {
        var ctx = ChatModelObservationContext.builder()
            .prompt(prompt).provider(PROVIDER).build();
        return Objects.requireNonNull(ChatModelObservationDocumentation.CHAT_MODEL_OPERATION
            .observation(this.observationConvention, DEFAULT_OBSERVATION_CONVENTION,
                () -> ctx, this.observationRegistry)
            .observe(() -> retryTemplate.execute(_ -> body.apply(ctx))));
    }

    private Map<String, ToolCallback> resolveToolCallbackMap(@Nullable ChatOptions options) {
        if (!(options instanceof ToolCallingChatOptions toolOptions)) {
            return Map.of();
        }
        return toolOptions.getToolCallbacks().stream()
            .collect(Collectors.toMap(cb -> cb.getToolDefinition().name(), Function.identity()));
    }

    private ToolContext resolveToolContext(@Nullable ChatOptions options) {
        if (options instanceof AnthropicChatOptions ao) {
            return new ToolContext(ao.getToolContext());
        }
        return new ToolContext(Map.of());
    }

    private StepJournal resolveJournal(@Nullable ChatOptions options) {
        if (options instanceof AnthropicChatOptions ao) {
            return ao.getJournal();
        }
        return StepJournal.NOOP;
    }

    private boolean hasToolCalls(Message response, Map<String, ToolCallback> callbackMap) {
        return response.stopReason().filter(StopReason.TOOL_USE::equals).isPresent() && !callbackMap.isEmpty();
    }

    private ChatResponse toChatResponse(Message message) {
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
            .content(text).toolCalls(toolCalls).build();

        var sdkUsage = message.usage();
        Usage usage = new Usage() {
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
            List.of(new Generation(assistantMessage, ChatGenerationMetadata.NULL)),
            ChatResponseMetadata.builder().model(message.model().toString()).usage(usage).build()
        );
    }

    private void advanceWithToolResults(
        MessageCreateParams.Builder builder, Message response,
        Map<String, ToolCallback> callbackMap, ToolContext toolContext, StepJournal journal
    ) {
        var contentBlocks = response.content().stream()
            .map(JournaledAnthropicChatModel::toRequestBlock)
            .toList();
        builder.addMessage(MessageParam.builder()
            .role(MessageParam.Role.ASSISTANT)
            .contentOfBlockParams(contentBlocks)
            .build());
        builder.addUserMessageOfBlockParams(executeTools(response, callbackMap, toolContext, journal));
    }

    private static IllegalStateException toolLoopExceeded() {
        return new IllegalStateException("Tool call loop exceeded " + MAX_TOOL_ROUNDS + " rounds");
    }

    private String toJson(JsonValue value) {
        try {
            return ObjectMappers.jsonMapper().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private static ContentBlockParam toRequestBlock(ContentBlock block) {
        if (block.isToolUse()) {
            var tu = block.asToolUse();
            return ContentBlockParam.ofToolUse(ToolUseBlockParam.builder()
                .id(tu.id()).name(tu.name()).input(tu._input()).build());
        }
        return block.toParam();
    }

    private List<ContentBlockParam> executeTools(
        Message response, Map<String, ToolCallback> callbackMap, ToolContext toolContext,
        StepJournal journal
    ) {
        return response.content().stream()
            .filter(ContentBlock::isToolUse)
            .map(b -> executeToolCall(b.asToolUse(), callbackMap, toolContext, journal))
            .toList();
    }

    private ContentBlockParam executeToolCall(
        ToolUseBlock toolUse, Map<String, ToolCallback> callbackMap, ToolContext toolContext,
        StepJournal journal
    ) {
        var name = toolUse.name();
        var callback = callbackMap.get(name);
        if (callback == null) {
            log.error("Unknown tool: {}", name);
            return toolResult(toolUse.id(), "{\"error\":\"Unknown tool: " + name + "\"}");
        }
        try {
            log.info("Executing tool: {} ({})", name, toolUse.id());
            var inputJson = toJson(toolUse._input());
            var result = journal.run("tool-" + name, String.class,
                () -> callback.call(inputJson, toolContext));
            log.debug("Tool {} returned {} chars", name, result.length());
            return toolResult(toolUse.id(), result);
        } catch (Exception e) {
            log.error("Tool {} failed: {}", name, e.getMessage(), e);
            var msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return toolResult(toolUse.id(), "{\"error\":true,\"tool\":\"%s\",\"message\":\"%s\"}".formatted(
                name.replace("\"", "\\\""), msg.replace("\"", "\\\"")));
        }
    }

    private static ContentBlockParam toolResult(String toolUseId, String content) {
        return ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
            .toolUseId(toolUseId).content(content).build());
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
        var callbackMap = resolveToolCallbackMap(prompt.getOptions());
        var toolCtx = resolveToolContext(prompt.getOptions());
        var journal = resolveJournal(prompt.getOptions());
        var builder = paramsBuilder.toBuilder(prompt);

        for (var round = 0; round <= MAX_TOOL_ROUNDS; round++) {
            var executed = new boolean[]{false};
            var message = journal.run("llm-stream-" + round, Message.class, () -> {
                executed[0] = true;
                return fixMissingToolInputs(streamRound(builder.build(), sink));
            });
            if (!executed[0]) {
                emitCachedRound(message, sink);
            }
            if (!hasToolCalls(message, callbackMap)) {
                sink.next(toChatResponse(message));
                sink.complete();
                return;
            }
            advanceWithToolResults(builder, message, callbackMap, toolCtx, journal);
        }
        sink.error(toolLoopExceeded());
    }

    private Message fixMissingToolInputs(Message message) {
        var content = message.content();
        var needsFix = content.stream().anyMatch(b ->
            b.isToolUse() && b.asToolUse()._input().isMissing());
        if (!needsFix) {
            return message;
        }

        return message.toBuilder().content(content.stream().map(block -> {
            if (block.isToolUse() && block.asToolUse()._input().isMissing()) {
                return ContentBlock.ofToolUse(block.asToolUse().toBuilder()
                    .input(JsonValue.from(Map.of()))
                    .build());
            }
            return block;
        }).toList()).build();
    }

    private Message streamRound(MessageCreateParams params, FluxSink<ChatResponse> sink) {
        var accumulator = MessageAccumulator.create();
        var thinkingStarted = new boolean[]{false};
        try (var stream = client.messages().createStreaming(params)) {
            stream.stream().peek(event -> {
                try {
                    accumulator.accumulate(event);
                } catch (Exception e) {
                    log.debug("Accumulator skipped: {}", e.getMessage());
                }
            }).forEach(event -> {
                event.contentBlockDelta().ifPresent(d -> emitDelta(d.delta(), sink, thinkingStarted));
                event.contentBlockStart().ifPresent(s -> emitBlockStart(s.contentBlock(), sink, thinkingStarted));
            });
        }
        return accumulator.message();
    }

    private void emitCachedRound(Message message, FluxSink<ChatResponse> sink) {
        for (var block : message.content()) {
            if (block.isText()) {
                sink.next(textChunk(block.asText().text()));
            } else if (block.isToolUse()) {
                sink.next(textChunk("[tool_call] " + block.asToolUse().name()));
            }
        }
    }

    private void emitDelta(RawContentBlockDelta delta, FluxSink<ChatResponse> sink, boolean[] thinkingStarted) {
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
        } else if (delta.isCitations()) {
            delta.asCitations().citation().webSearchResultLocation().ifPresent(loc ->
                sink.next(textChunk("[citation] " + loc.url()
                                    + loc.title().map(t -> " — " + t).orElse(""))));
        }
    }

    private void emitBlockStart(
        RawContentBlockStartEvent.ContentBlock block, FluxSink<ChatResponse> sink, boolean[] thinkingStarted
    ) {
        if (block.isToolUse()) {
            thinkingStarted[0] = false;
            sink.next(textChunk("[tool_call] " + block.asToolUse().name()));
        } else if (block.isServerToolUse()) {
            thinkingStarted[0] = false;
            sink.next(textChunk(formatServerToolStart(block.asServerToolUse())));
        } else if (block.isWebSearchToolResult()) {
            var content = block.asWebSearchToolResult().content();
            if (content.isResultBlocks()) {
                var links = content.asResultBlocks().stream()
                    .map(r -> "  " + r.title() + " — " + r.url())
                    .collect(Collectors.joining("\n"));
                sink.next(textChunk("[search_results]\n" + links));
            } else if (content.isError()) {
                sink.next(textChunk("[search_error] " + content.asError().errorCode()));
            }
        }
    }

    private static ChatResponse textChunk(String text) {
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
