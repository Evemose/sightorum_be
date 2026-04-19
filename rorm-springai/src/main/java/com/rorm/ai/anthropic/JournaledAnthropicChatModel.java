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
import com.rorm.ai.chat.ServerToolMessage;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
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
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
public class JournaledAnthropicChatModel implements ChatModel {

    private static final String PROVIDER = "anthropic";
    private static final int MAX_TOOL_ROUNDS = 20;
    private static final ChatModelObservationConvention DEFAULT_OBSERVATION_CONVENTION =
        new DefaultChatModelObservationConvention();

    private final AnthropicClient directClient;
    private final AnthropicClient bedrockClient;
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
    private static String extractServerToolQuery(ServerToolUseBlock serverTool) {
        var map = (Map<String, JsonValue>) serverTool._input().asObject().orElse(null);
        if (map != null && map.containsKey("query")) {
            var query = map.get("query").asString().orElse(null);
            return query != null ? query.toString() : null;
        }
        return null;
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

    private static ChatResponse thinkingChunk(String text) {
        return new ChatResponse(List.of(new ThinkingGeneration(text)));
    }

    private static ChatResponse toolCallChunk(String name) {
        return new ChatResponse(List.of(new StreamToolCallGeneration(name)));
    }

    private static ChatResponse serverToolChunk(ServerToolUseBlock serverTool) {
        var name = serverTool.name().toString();
        var query = extractServerToolQuery(serverTool);
        return new ChatResponse(List.of(
            new ServerToolGeneration(new ServerToolMessage(name, query, null))));
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
            var client = resolveClient(prompt.getOptions());
            var rounds = new ArrayList<ToolRound>();

            for (var round = 0; round <= MAX_TOOL_ROUNDS; round++) {
                var strategy = computeCachingStrategy(rounds, round, cachingStrategyFn);
                var builder = paramsBuilder.toBuilder(prompt, strategy);
                addToolRounds(builder, rounds, strategy);
                resolveModelForClient(builder, client, prompt.getOptions());
                var params = builder.build();
                var response = journal.run("llm-" + round, Message.class,
                    () -> throttle.execute(
                        estimateUsage(params),
                        () -> client.messages().create(params),
                        TokenUsage::from));
                if (!hasToolCalls(response, callbackMap)) {
                    var result = toChatResponse(response, rounds);
                    observationCtx.setResponse(result);
                    return result;
                }
                var toolRound = buildToolRound(response, callbackMap, toolCtx);
                rounds.add(toolRound);
            }
            throw toolLoopExceeded();
        });
    }

    private CacheTTL computeCachingStrategy(
        List<ToolRound> rounds,
        int currentRound,
        CacheStrategy strategyFn
    ) {
        if (strategyFn == null) {
            return CacheTTL.NONE;
        }
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

    @SuppressWarnings("unchecked")
    private JsonNode fixObjectStrings(JsonValue jsonValue) {
        var om = ObjectMappers.jsonMapper();
        if (jsonValue.asString().isPresent()) {
            jsonValue = JsonValue.fromJsonNode(om.valueToTree(jsonValue.asString().get()));
        }
        var finalJsonValue = jsonValue;
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
            .orElseGet(() -> toJsonNode(finalJsonValue));

    }

    private StepJournal resolveJournal(@Nullable ChatOptions options) {
        if (options instanceof AnthropicChatOptions ao) {
            return ao.getJournal();
        }
        return StepJournal.DEFAULT;
    }

    private void doStream(Prompt prompt, FluxSink<ChatResponse> sink) {
        var callbackMap = resolveToolCallbackMap(prompt.getOptions());
        var toolCtx = resolveToolContext(prompt.getOptions());
        var journal = resolveJournal(prompt.getOptions());
        var cachingStrategyFn = resolveCachingStrategyFunction(prompt.getOptions());
        var client = resolveClient(prompt.getOptions());
        var rounds = new ArrayList<ToolRound>();

        for (var round = 0; round <= MAX_TOOL_ROUNDS; round++) {
            var strategy = computeCachingStrategy(rounds, round, cachingStrategyFn);
            var builder = paramsBuilder.toBuilder(prompt, strategy);
            addToolRounds(builder, rounds, strategy);
            resolveModelForClient(builder, client, prompt.getOptions());
            var params = builder.build();
            log.info("Calling {} with model={}", client == bedrockClient ? "bedrock" : "direct", params.model());
            var executed = new boolean[]{false};
            var message = journal.run("llm-stream-" + round, Message.class, () -> {
                executed[0] = true;
                return fixMissingToolInputs(streamRound(params, sink, client));
            });
            if (!executed[0]) {
                emitCachedRound(message, sink);
            }
            if (!hasToolCalls(message, callbackMap)) {
                emitCustomGenerations(message, rounds, sink);
                sink.complete();
                return;
            }
            var toolRound = buildToolRound(message, callbackMap, toolCtx);
            rounds.add(toolRound);
        }
        sink.error(toolLoopExceeded());
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

    private CacheStrategy resolveCachingStrategyFunction(
        @Nullable ChatOptions options
    ) {
        if (options instanceof AnthropicChatOptions ao) {
            return ao.getCachingStrategyFunction();
        }
        return _ -> CacheTTL.NONE;
    }

    private ChatResponse toChatResponse(Message message, List<ToolRound> rounds) {
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

        var generations = new ArrayList<Generation>();
        generations.add(new Generation(assistantMessage, ChatGenerationMetadata.NULL));
        addCustomGenerations(generations, thinking, rounds, serverToolCalls);

        return new ChatResponse(
            generations,
            ChatResponseMetadata.builder().model(message.model().toString()).usage(usage).build()
        );
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

    private static IllegalStateException toolLoopExceeded() {
        return new IllegalStateException("Tool call loop exceeded " + MAX_TOOL_ROUNDS + " rounds");
    }

    private void addCustomGenerations(
        List<Generation> generations, String thinking,
        List<ToolRound> rounds, List<Map<String, Object>> serverToolCalls
    ) {
        if (!thinking.isEmpty()) {
            generations.add(new ThinkingGeneration(thinking));
        }
        for (var round : rounds) {
            var roundToolCalls = round.fullAssistantBlocks().stream()
                .filter(ContentBlockParam::isToolUse)
                .map(b -> {
                    var tu = b.asToolUse();
                    return new AssistantMessage.ToolCall(tu.id(), "function", tu.name(), safeToolInput(tu));
                })
                .toList();
            var roundText = round.fullAssistantBlocks().stream()
                .filter(ContentBlockParam::isText)
                .map(b -> b.asText().text())
                .collect(Collectors.joining("\n"));
            var roundAssistant = AssistantMessage.builder()
                .content(roundText).toolCalls(roundToolCalls).build();
            var toolResponses = round.toolResults().stream()
                .map(tr -> new ToolResponseMessage.ToolResponse(
                    tr.toolUseId(),
                    extractToolName(tr.toolUseId(), round),
                    tr.content()
                ))
                .toList();
            var toolResponseMsg = ToolResponseMessage.builder().responses(toolResponses).build();
            generations.add(new ToolRoundGeneration(roundAssistant, toolResponseMsg));
        }
        for (var stc : serverToolCalls) {
            generations.add(new ServerToolGeneration(new com.rorm.ai.chat.ServerToolMessage(
                (String) stc.get("toolName"),
                (String) stc.get("inputJson"),
                (String) stc.get("outputJson")
            )));
        }
    }

    private String toJson(Object value) {
        try {
            return ObjectMappers.jsonMapper().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private AnthropicClient resolveClient(@Nullable ChatOptions options) {
//        if (options instanceof AnthropicChatOptions ao && ao.isWebAccess()) {
//            return directClient;
//        }
//        return bedrockClient;
        return directClient;
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
            .onBackpressureBuffer();
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

    private void resolveModelForClient(MessageCreateParams.Builder builder,
                                       AnthropicClient client, @Nullable ChatOptions options) {
        if (client == directClient) {
            return;
        }
        var map = Map.of(
            "claude-sonnet-4-6", "us.anthropic.claude-sonnet-4-6",
            "claude-opus-4-6", "us.anthropic.claude-opus-4-6-v1",
            "claude-haiku-4-5", "us.anthropic.claude-haiku-4-5-20251001-v1:0",
            "claude-opus-4-7", "us.anthropic.claude-opus-4-7"
        );

        var model = options != null && options.getModel() != null
            ? options.getModel() : AnthropicParamsBuilder.DEFAULT_MODEL;
        if (model.contains(".") || model.contains(":")) {
            if (!map.containsValue(model)) {
                throw new IllegalArgumentException(
                    "Full model name " + model + " for bedrock client is unknown. Supported models: " + map.keySet()
                );
            }
            return;
        }
        var modelName = map.get(model);
        if (modelName == null) {
            throw new IllegalArgumentException(
                "Model " + model + " is not supported on bedrock client. Supported models: " + map.keySet()
            );
        }
        builder.model(modelName);
    }

    private void emitCustomGenerations(Message message, List<ToolRound> rounds, FluxSink<ChatResponse> sink) {
        var thinking = message.content().stream()
            .filter(ContentBlock::isThinking)
            .map(b -> b.asThinking().thinking())
            .collect(Collectors.joining("\n"));
        var serverToolCalls = extractServerToolCalls(message);

        var generations = new ArrayList<Generation>();
        generations.add(new Generation(new AssistantMessage("")));
        addCustomGenerations(generations, thinking, rounds, serverToolCalls);

        if (generations.size() > 1) {
            sink.next(new ChatResponse(generations));
        }
    }

    @SneakyThrows
    private Message streamRound(MessageCreateParams params, FluxSink<ChatResponse> sink, AnthropicClient client) {
        var accumulator = MessageAccumulator.create();
        try (var stream = client.messages().createStreaming(params)) {
            stream.stream().peek(event -> {
                try {
                    accumulator.accumulate(event);
                } catch (Exception e) {
                    log.debug("Accumulator skipped: {}", e.getMessage());
                }
            }).forEach(event -> {
                event.contentBlockDelta().ifPresent(d -> emitDelta(d.delta(), sink));
                event.contentBlockStart().ifPresent(s -> emitBlockStart(s.contentBlock(), sink));
            });
        }
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
                sink.next(thinkingChunk(block.asThinking().thinking()));
            } else if (block.isToolUse()) {
                sink.next(toolCallChunk(block.asToolUse().name()));
                sink.next(textChunk("\n"));
            } else if (block.isServerToolUse()) {
                sink.next(serverToolChunk(block.asServerToolUse()));
            } else if (block.isWebSearchToolResult()) {
                emitWebSearchResult(block.asWebSearchToolResult().content(), sink);
            }
        }
    }

    private void emitDelta(RawContentBlockDelta delta, FluxSink<ChatResponse> sink) {
        if (delta.isText()) {
            sink.next(textChunk(delta.asText().text()));
        } else if (delta.isThinking()) {
            sink.next(thinkingChunk(delta.asThinking().thinking()));
        } else if (delta.isCitations()) {
            delta.asCitations().citation().webSearchResultLocation().ifPresent(loc ->
                sink.next(textChunk("[citation] " + loc.url()
                                    + loc.title().map(t -> " — " + t).orElse(""))));
        }
    }

    private void emitBlockStart(
        RawContentBlockStartEvent.ContentBlock block, FluxSink<ChatResponse> sink
    ) {
        sink.next(textChunk("\n"));
        if (block.isToolUse()) {
            sink.next(toolCallChunk(block.asToolUse().name()));
        } else if (block.isServerToolUse()) {
            sink.next(serverToolChunk(block.asServerToolUse()));
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
