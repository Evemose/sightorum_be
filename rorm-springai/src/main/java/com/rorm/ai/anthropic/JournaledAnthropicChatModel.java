package com.rorm.ai.anthropic;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
import com.anthropic.helpers.MessageAccumulator;
import com.anthropic.models.messages.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.rorm.DurableFuture;
import com.rorm.StepJournal;
import com.rorm.ai.DeferredToolResult;
import com.rorm.ai.anthropic.AnthropicChatOptions.CacheTTL;
import com.rorm.ai.anthropic.AnthropicChatOptions.RoundContext;
import com.rorm.ai.anthropic.AnthropicChatOptions.ToolRoundInfo;
import com.rorm.ai.chat.CacheStrategy;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
public class JournaledAnthropicChatModel implements ChatModel {

    private static final String PROVIDER = "anthropic";
    private static final int MAX_TOOL_ROUNDS = 20;
    private static final ChatModelObservationConvention DEFAULT_OBSERVATION_CONVENTION =
        new DefaultChatModelObservationConvention();

    private final AnthropicClient client;
    private final AnthropicParamsBuilder paramsBuilder;
    private final TokenThrottle throttle;
    private final ObservationRegistry observationRegistry;
    private final ChatModelObservationConvention observationConvention = DEFAULT_OBSERVATION_CONVENTION;

    private static String writeAnthropicJson(Object value) {
        try {
            return ObjectMappers.jsonMapper().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{}";
        }
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

    private static void emitWebSearchResult(
        WebSearchToolResultBlockContent content, FluxSink<ChatResponse> sink
    ) {
        if (content.isResultBlocks()) {
            var links = content.asResultBlocks().stream()
                .map(r -> "  " + r.title() + " — " + r.url())
                .collect(Collectors.joining("\n"));
            sink.next(textChunk("[search_results]\n" + links));
        } else if (content.isError()) {
            sink.next(textChunk("[search_error] " + content.asError().errorCode()));
        }
    }

    private static ChatResponse textChunk(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
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

    private static List<ContentBlockParam> toToolResultBlocks(
        List<ToolCallResult> results, boolean cacheLastBlock, CacheControlEphemeral.Ttl cacheTtl
    ) {
        var blocks = new ArrayList<ContentBlockParam>(results.size());
        for (int i = 0; i < results.size(); i++) {
            var r = results.get(i);
            var trBuilder = ToolResultBlockParam.builder()
                .toolUseId(r.toolUseId()).content(r.content());
            if (cacheLastBlock && i == results.size() - 1) {
                trBuilder.cacheControl(CacheControlEphemeral.builder().ttl(cacheTtl).build());
            }
            blocks.add(ContentBlockParam.ofToolResult(trBuilder.build()));
        }
        return blocks;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return withObservation(prompt, observationCtx -> {
            var callbackMap = resolveToolCallbackMap(prompt.getOptions());
            var toolCtx = resolveToolContext(prompt.getOptions());
            var journal = resolveJournal(prompt.getOptions());
            var cachingStrategyFn = resolveCachingStrategyFunction(prompt.getOptions());
            var rounds = new ArrayList<ToolRound>();

            for (var round = 0; round <= MAX_TOOL_ROUNDS; round++) {
                var strategy = computeCachingStrategy(rounds, round, cachingStrategyFn);
                var builder = paramsBuilder.toBuilder(prompt, strategy);
                addToolRounds(builder, rounds, strategy);
                var params = builder.build();
                var response = journal.run("llm-" + round, Message.class,
                    () -> throttle.execute(
                        estimateUsage(params),
                        () -> client.messages().create(params),
                        TokenUsage::from));
                if (!hasToolCalls(response, callbackMap)) {
                    var result = toChatResponse(response);
                    observationCtx.setResponse(result);
                    return result;
                }
                var toolRound = buildToolRound(response, callbackMap, toolCtx);
                rounds.add(toolRound);
            }
            throw toolLoopExceeded();
        });
    }

    private StepJournal resolveJournal(@Nullable ChatOptions options) {
        if (options instanceof AnthropicChatOptions ao) {
            return ao.getJournal();
        }
        return StepJournal.DEFAULT;
    }

    private static UsageConsuming estimateUsage(MessageCreateParams params) {
        long chars = 0;
        for (var msg : params.messages()) {
            chars += msg.toString().length();
        }
        if (params.system().isPresent()) {
            chars += params.system().get().toString().length();
        }
        return TokenUsage.estimate(chars / 4, params.model().toString());
    }

    private static IllegalStateException toolLoopExceeded() {
        return new IllegalStateException("Tool call loop exceeded " + MAX_TOOL_ROUNDS + " rounds");
    }

    private CacheStrategy resolveCachingStrategyFunction(
        @Nullable ChatOptions options
    ) {
        if (options instanceof AnthropicChatOptions ao) {
            return ao.getCachingStrategyFunction();
        }
        return _ -> CacheTTL.NONE;
    }

    private CacheTTL computeCachingStrategy(
        List<ToolRound> rounds,
        int currentRound,
        CacheStrategy strategyFn
    ) {
        var previousRounds = new ArrayList<ToolRoundInfo>();

        for (var round : rounds) {
            // Build AssistantMessage from assistant blocks
            var textContent = round.fullAssistantBlocks().stream()
                .filter(ContentBlockParam::isText)
                .map(b -> b.asText().text())
                .collect(Collectors.joining("\n"));

            var toolCalls = round.fullAssistantBlocks().stream()
                .filter(ContentBlockParam::isToolUse)
                .map(b -> {
                    var tu = b.asToolUse();
                    var inputJson = safeToolInput(tu);
                    return new AssistantMessage.ToolCall(
                        tu.id(),
                        "function",
                        tu.name(),
                        inputJson
                    );
                })
                .toList();

            var assistantMessage = AssistantMessage.builder()
                .content(textContent)
                .toolCalls(toolCalls)
                .build();

            // Build UserMessage for tool results
            var toolResultsContent = round.toolResults().stream()
                .map(tr -> "[" + extractToolName(tr.toolUseId(), round) + "]\n" + tr.content())
                .collect(Collectors.joining("\n\n"));

            var toolResultsMessage = new UserMessage(toolResultsContent);

            // Build ToolResult records
            var toolResults = round.toolResults().stream()
                .map(tr -> new AnthropicChatOptions.ToolResult(
                    extractToolName(tr.toolUseId(), round),
                    tr.toolUseId(),
                    tr.content()
                ))
                .toList();

            previousRounds.add(new ToolRoundInfo(
                assistantMessage,
                toolResultsMessage,
                Set.copyOf(round.calledToolNames()),
                toolResults
            ));
        }

        var context = new RoundContext(currentRound, List.copyOf(previousRounds));
        return strategyFn.strategy(context);
    }

    private void addToolRounds(
        MessageCreateParams.Builder builder, List<ToolRound> rounds, CacheTTL strategy
    ) {
        for (int i = 0; i < rounds.size(); i++) {
            var round = rounds.get(i);
            var isLast = (i == rounds.size() - 1);
            var blocks = isLast ? round.fullAssistantBlocks() : round.leanAssistantBlocks();
            builder.addMessage(MessageParam.builder()
                .role(MessageParam.Role.ASSISTANT)
                .contentOfBlockParams(blocks)
                .build());

            // Apply caching based on strategy
            var cacheBoundary = !isLast && (i == rounds.size() - 2);
            var shouldCache = strategy != CacheTTL.NONE && cacheBoundary;
            var cacheTtl = strategy == CacheTTL.LONG
                ? CacheControlEphemeral.Ttl.TTL_1H
                : CacheControlEphemeral.Ttl.TTL_5M;

            builder.addUserMessageOfBlockParams(
                toToolResultBlocks(round.toolResults(), shouldCache, cacheTtl));
        }
    }

    private String safeToolInput(ToolUseBlockParam tu) {
        if (tu._input().isMissing()) {
            return "{}";
        }
        try {
            return toJson(tu.input());
        } catch (Exception e) {
            return "{}";
        }
    }

    private String extractToolName(String toolUseId, ToolRound round) {
        // Find tool name from assistant blocks
        return round.fullAssistantBlocks().stream()
            .filter(ContentBlockParam::isToolUse)
            .map(ContentBlockParam::asToolUse)
            .filter(tu -> tu.id().equals(toolUseId))
            .map(ToolUseBlockParam::name)
            .findFirst()
            .orElse("unknown");
    }

    private ToolRound buildToolRound(
        Message response, Map<String, ToolCallback> callbackMap,
        ToolContext toolContext
    ) {
        var fullBlocks = response.content().stream()
            .map(JournaledAnthropicChatModel::toRequestBlock)
            .toList();
        var hasThinking = response.content().stream().anyMatch(ContentBlock::isThinking);
        var leanBlocks = hasThinking
            ? response.content().stream()
            .filter(b -> !b.isThinking())
            .map(JournaledAnthropicChatModel::toRequestBlock)
            .toList()
            : fullBlocks;
        var calledTools = response.content().stream()
            .filter(ContentBlock::isToolUse)
            .map(b -> b.asToolUse().name())
            .collect(Collectors.toSet());
        var toolResults = executeTools(response, callbackMap, toolContext);
        return new ToolRound(fullBlocks, leanBlocks, toolResults, calledTools);
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
                return new AssistantMessage.ToolCall(tu.id(), "function", tu.name(), toJson(fixObjectStrings(
                    tu._input()
                )));
            })
            .toList();

        var metadata = new HashMap<String, Object>();

        var thinking = message.content().stream()
            .filter(ContentBlock::isThinking)
            .map(b -> b.asThinking().thinking())
            .collect(Collectors.joining("\n"));
        if (!thinking.isEmpty()) {
            metadata.put("thinking", thinking);
        }

        var serverToolCalls = extractServerToolCalls(message);
        if (!serverToolCalls.isEmpty()) {
            metadata.put("serverToolCalls", serverToolCalls);
        }

        var assistantMessage = AssistantMessage.builder()
            .content(text).toolCalls(toolCalls).properties(metadata).build();

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
            List.of(new Generation(assistantMessage, ChatGenerationMetadata.NULL)),
            ChatResponseMetadata.builder().model(message.model().toString()).usage(usage).build()
        );
    }

    private String toJson(Object value) {
        try {
            return ObjectMappers.jsonMapper().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private JsonNode fixObjectStrings(JsonValue jsonValue) {
        var om = ObjectMappers.jsonMapper();
        return ((Optional<Map<String, JsonValue>>) jsonValue.asObject())
            .map(map -> map.entrySet().stream()
                .collect(Collectors.toMap(
                    Map.Entry::getKey,
                    entry -> {
                        var strValue = ((Optional<String>) entry.getValue().asString());
                        return strValue
                            .filter(s -> s.startsWith("{") || s.startsWith("["))
                            .flatMap(this::tryReadTree)
                            .orElseGet(() -> toJsonNode(entry.getValue()));
                    }
                )))
            .<JsonNode>map(om::valueToTree)
            .orElseGet(() -> toJsonNode(jsonValue));

    }

    private ChatResponse withObservation(Prompt prompt, Function<ChatModelObservationContext, ChatResponse> body) {
        var ctx = ChatModelObservationContext.builder()
            .prompt(prompt).provider(PROVIDER).build();
        return Objects.requireNonNull(ChatModelObservationDocumentation.CHAT_MODEL_OPERATION
            .observation(this.observationConvention, DEFAULT_OBSERVATION_CONVENTION,
                () -> ctx, this.observationRegistry)
            .observe(() -> body.apply(ctx)));
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

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.<ChatResponse>create(sink -> doStream(prompt, sink))
            .subscribeOn(Schedulers.boundedElastic())
            .windowUntil(batchingBoundary())
            .flatMap(f -> f.reduce((a, b) -> new ChatResponse(
                List.of(new Generation(
                    new AssistantMessage(
                        a.getResult().getOutput().getText() + b.getResult().getOutput().getText()
                    )
                )),
                b.getMetadata()
            ))).onBackpressureBuffer();
    }

    private boolean hasToolCalls(Message response, Map<String, ToolCallback> callbackMap) {
        return response.stopReason().filter(StopReason.TOOL_USE::equals).isPresent() && !callbackMap.isEmpty();
    }

    private Optional<JsonNode> tryReadTree(String json) {
        var maxBalancingParens = 3;
        var combs = new HashSet<>(Set.of(json));
        for (var i = 0; i < maxBalancingParens; i++) {
            // add extra paren
            combs.add(json + String.valueOf(inverse(json.charAt(0))).repeat(i + 1));
            // also may be overshoot - fix extra paren
            if (json.charAt(json.length() - 1 - i) == inverse(json.charAt(0))) {
                combs.add(json.substring(0, json.length() - i));
            }
        }
        for (var attempt : combs) {
            try {
                var om = ObjectMappers.jsonMapper();
                return Optional.of(om.readTree(attempt));
            } catch (JsonProcessingException _) {

            }
        }
        return Optional.empty();
    }

    private JsonNode toJsonNode(Object obj) {
        return ObjectMappers.jsonMapper().convertValue(obj, JsonNode.class);
    }

    private char inverse(char c) {
        return switch (c) {
            case '{' -> '}';
            case '[' -> ']';
            default -> c;
        };
    }

    private List<Map<String, Object>> extractServerToolCalls(Message message) {
        var result = new ArrayList<Map<String, Object>>();
        String currentToolName = null;
        String currentInputJson = null;

        for (var block : message.content()) {
            if (block.isServerToolUse()) {
                var stu = block.asServerToolUse();
                currentToolName = stu.name().toString();
                currentInputJson = toJson(stu._input());
            } else if (block.isWebSearchToolResult()) {
                result.add(Map.of(
                    "toolName", currentToolName != null ? currentToolName : "unknown",
                    "inputJson", currentInputJson != null ? currentInputJson : "{}",
                    "outputJson", writeAnthropicJson(block.asWebSearchToolResult().content())
                ));
                currentToolName = null;
                currentInputJson = null;
            }
        }
        return result;
    }

    private List<ToolCallResult> executeTools(
        Message response, Map<String, ToolCallback> callbackMap, ToolContext toolContext
    ) {
        var toolCalls = response.content().stream()
            .filter(ContentBlock::isToolUse)
            .map(ContentBlock::asToolUse)
            .toList();

        var results = new ArrayList<ToolCallResult>(toolCalls.size());
        var collector = DeferredToolResult.createCollector();

        // Place the shared mutable collector in the base context so each tool copy inherits it
        var baseCtx = new HashMap<>(toolContext.getContext());
        baseCtx.put(DeferredToolResult.COLLECTOR_KEY, collector);

        for (var tc : toolCalls) {
            results.add(executeToolCall(tc, callbackMap, new ToolContext(baseCtx)));
        }

        if (!collector.isEmpty()) {
            log.info("Awaiting {} deferred tool results", collector.size());
            DurableFuture.all(collector.values().toArray(DurableFuture[]::new)).await();
            for (int i = 0; i < results.size(); i++) {
                var r = results.get(i);
                var future = collector.get(toolCalls.get(i).id());
                if (future != null) {
                    results.set(i, new ToolCallResult(r.toolUseId(), future.await()));
                }
            }
        }

        return results;
    }

    private ToolCallResult executeToolCall(
        ToolUseBlock toolUse, Map<String, ToolCallback> callbackMap, ToolContext toolContext
    ) {
        var name = toolUse.name();
        var callback = callbackMap.get(name);
        if (callback == null) {
            log.error("Unknown tool: {}", name);
            return new ToolCallResult(toolUse.id(), "{\"error\":\"Unknown tool: " + name + "\"}");
        }
        try {
            log.info("Executing tool: {} ({})", name, toolUse.id());
            var inputJson = toJson(fixObjectStrings(toolUse._input()));
            var ctxMapWithId = new HashMap<>(toolContext.getContext());
            ctxMapWithId.put("id", toolUse.id());
            var result = callback.call(inputJson, new ToolContext(ctxMapWithId));
            log.debug("Tool {} returned {} chars", name, result.length());
            return new ToolCallResult(toolUse.id(), result);
        } catch (Exception e) {
            log.error("Tool {} ({}) failed: {}", toolUse.id(), name, e.getMessage(), e);
            var msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return new ToolCallResult(toolUse.id(), "{\"error\":true,\"tool\":\"%s\",\"message\":\"%s\"}".formatted(
                name.replace("\"", "\\\""), msg.replace("\"", "\\\"")));
        }
    }

    private void doStream(Prompt prompt, FluxSink<ChatResponse> sink) {
        var callbackMap = resolveToolCallbackMap(prompt.getOptions());
        var toolCtx = resolveToolContext(prompt.getOptions());
        var journal = resolveJournal(prompt.getOptions());
        var cachingStrategyFn = resolveCachingStrategyFunction(prompt.getOptions());
        var rounds = new ArrayList<ToolRound>();

        for (var round = 0; round <= MAX_TOOL_ROUNDS; round++) {
            var strategy = computeCachingStrategy(rounds, round, cachingStrategyFn);
            var builder = paramsBuilder.toBuilder(prompt, strategy);
            addToolRounds(builder, rounds, strategy);
            var params = builder.build();
            var executed = new boolean[]{false};
            var message = journal.run("llm-stream-" + round, Message.class, () -> {
                executed[0] = true;
                return throttle.execute(
                    estimateUsage(params),
                    () -> fixMissingToolInputs(streamRound(params, sink)),
                    TokenUsage::from);
            });
            if (!executed[0]) {
                emitCachedRound(message, sink);
            }
            if (!hasToolCalls(message, callbackMap)) {
                sink.complete();
                return;
            }
            var toolRound = buildToolRound(message, callbackMap, toolCtx);
            rounds.add(toolRound);
        }
        sink.error(toolLoopExceeded());
    }

    // this is necessary to account for spring AI bug stalling downstream subscribers due to publishOn with default buffer (256),
    // that never re-requests, so after 256 elements downstream just stops receiving updates
    private static @NonNull Predicate<ChatResponse> batchingBoundary() {
        return new Predicate<>() {
            private static final int MIN_CHARS = 100;
            private static final int MAX_CHARS = 5000;
            private static final int BUDGET = 256;
            // midpoint: where the ramp is steepest (% of budget used)
            private static final double MIDPOINT = 0.6;
            // steepness: higher = sharper transition
            private static final double STEEPNESS = 12.0;
            private int chars = 0;
            private int windowsEmitted = 0;

            @Override
            public boolean test(ChatResponse cr) {
                var text = Objects.requireNonNullElse(
                    cr.getResult().getOutput().getText(), "");
                chars += text.length();

                if (chars >= charThreshold()) {
                    chars = 0;
                    windowsEmitted++;
                    return true;
                }
                return false;
            }

            private int charThreshold() {
                double x = (double) windowsEmitted / BUDGET;
                double sigmoid = 1.0 / (1.0 + Math.exp(-STEEPNESS * (x - MIDPOINT)));
                return (int) (MIN_CHARS + (MAX_CHARS - MIN_CHARS) * sigmoid);
            }
        };
    }

    @SneakyThrows
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
        log.info("{}", ObjectMappers.jsonMapper().writeValueAsString(accumulator.message()));
        return accumulator.message();
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

    private void emitCachedRound(Message message, FluxSink<ChatResponse> sink) {
        for (var block : message.content()) {
            if (block.isText()) {
                sink.next(textChunk(block.asText().text()));
            } else if (block.isThinking()) {
                sink.next(textChunk("[thinking] " + block.asThinking().thinking()));
            } else if (block.isToolUse()) {
                sink.next(textChunk("[tool_call] " + block.asToolUse().name() + "\n"));
            } else if (block.isServerToolUse()) {
                sink.next(textChunk(formatServerToolStart(block.asServerToolUse())));
            } else if (block.isWebSearchToolResult()) {
                emitWebSearchResult(block.asWebSearchToolResult().content(), sink);
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
        sink.next(textChunk("\n"));
        if (block.isToolUse()) {
            thinkingStarted[0] = false;
            sink.next(textChunk("[tool_call] " + block.asToolUse().name()));
        } else if (block.isServerToolUse()) {
            thinkingStarted[0] = false;
            sink.next(textChunk(formatServerToolStart(block.asServerToolUse())));
        } else if (block.isWebSearchToolResult()) {
            emitWebSearchResult(block.asWebSearchToolResult().content(), sink);
        }
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return AnthropicChatOptions.builder().build();
    }

    private record ToolCallResult(String toolUseId, String content) {}

    private record ToolRound(
        List<ContentBlockParam> fullAssistantBlocks,
        List<ContentBlockParam> leanAssistantBlocks,
        List<ToolCallResult> toolResults,
        Set<String> calledToolNames) {}
}
