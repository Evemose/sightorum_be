package com.rorm.ai.anthropic;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.*;
import com.anthropic.services.blocking.MessageService;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.io.File;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.stream.Stream;

public class ScriptedAnthropicClient {

    public static AnthropicClient withCallCounter(AtomicInteger callCounter) {
        var messageService = proxy(MessageService.class, (method, args) -> {
            if (!method.equals("create")) {
                return null;
            }
            callCounter.incrementAndGet();
            var params = (MessageCreateParams) args[0];
            if (params.messages().size() > 1) {
                return textMessage("File exists and is accessible.");
            }
            return toolUseMessage("call_1", "checkFile");
        });
        return proxy(AnthropicClient.class, (method, _) ->
            method.equals("messages") ? messageService : null);
    }

    @SuppressWarnings("unchecked")
    static <T> T proxy(Class<T> iface, BiFunction<String, Object[], Object> handler) {
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class[]{iface},
            (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) {
                    return switch (method.getName()) {
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        case "toString" -> iface.getSimpleName() + "@proxy";
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                }
                var result = handler.apply(method.getName(), args);
                if (result != null) {
                    return result;
                }
                throw new UnsupportedOperationException(method.getName());
            });
    }

    public static Message textMessage(String text) {
        return messageBuilder()
            .stopReason(StopReason.END_TURN)
            .addContent(TextBlock.builder().text(text).citations(List.of()).build())
            .build();
    }

    public static Message toolUseMessage(String toolCallId, String toolName) {
        return messageBuilder()
            .stopReason(StopReason.TOOL_USE)
            .addContent(ToolUseBlock.builder()
                .id(toolCallId)
                .name(toolName)
                .input(JsonValue.from(Map.of()))
                .caller(ToolUseBlock.Caller.ofDirect(DirectCaller.builder().build()))
                .build())
            .build();
    }

    private static Message.Builder messageBuilder() {
        return Message.builder()
            .id("msg_" + UUID.randomUUID())
            .model("test-model")
            .container((Container) null)
            .stopSequence((String) null)
            .usage(Usage.builder()
                .inputTokens(100)
                .outputTokens(50)
                .cacheCreationInputTokens(0L)
                .cacheReadInputTokens(0L)
                .cacheCreation(CacheCreation.builder()
                    .ephemeral5mInputTokens(0)
                    .ephemeral1hInputTokens(0)
                    .build())
                .serverToolUse((ServerToolUsage) null)
                .serviceTier((Usage.ServiceTier) null)
                .inferenceGeo((String) null)
                .build());
    }

    public static AnthropicClient multiRoundClient(AtomicInteger callCounter) {
        var messageService = proxy(MessageService.class, (method, args) -> {
            if (!method.equals("create")) {
                return null;
            }
            callCounter.incrementAndGet();
            var params = (MessageCreateParams) args[0];
            return switch (params.messages().size()) {
                case 1 -> toolUseMessage("call_a", "checkFileA");
                case 3 -> toolUseMessage("call_b", "checkFileB");
                default -> textMessage("Both files exist and are accessible.");
            };
        });
        return proxy(AnthropicClient.class, (method, _) ->
            method.equals("messages") ? messageService : null);
    }

    public static ToolCallback trackedFileCheckCallback(String name, String filePath, AtomicInteger counter) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                    .name(name)
                    .description("Check if a file exists")
                    .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                    .build();
            }

            @Override
            public String call(String toolInput) {
                counter.incrementAndGet();
                var file = new File(filePath);
                if (!file.exists()) {
                    throw new AssertionError("File not found: " + filePath);
                }
                return "File exists: " + filePath + " (size: " + file.length() + " bytes)";
            }
        };
    }

    public static AnthropicClient withStreamingCallCounter(AtomicInteger callCounter) {
        var messageService = proxy(MessageService.class, (method, args) -> {
            if (method.equals("create")) {
                callCounter.incrementAndGet();
                var params = (MessageCreateParams) args[0];
                if (params.messages().size() > 1) {
                    return textMessage("File exists and is accessible.");
                }
                return toolUseMessage("call_1", "checkFile");
            }
            if (method.equals("createStreaming")) {
                callCounter.incrementAndGet();
                var params = (MessageCreateParams) args[0];
                var msg = params.messages().size() > 1
                    ? textMessage("File exists and is accessible.")
                    : toolUseMessage("call_1", "checkFile");
                return streamResponseFor(msg);
            }
            return null;
        });
        return proxy(AnthropicClient.class, (method, _) ->
            method.equals("messages") ? messageService : null);
    }

    static StreamResponse<RawMessageStreamEvent> streamResponseFor(Message message) {
        var events = new ArrayList<RawMessageStreamEvent>();
        events.add(RawMessageStreamEvent.ofMessageStart(
            RawMessageStartEvent.builder().message(message).build()));
        for (var i = 0; i < message.content().size(); i++) {
            var block = message.content().get(i);
            RawContentBlockStartEvent.ContentBlock startBlock;
            if (block.isText()) {
                startBlock = RawContentBlockStartEvent.ContentBlock.ofText(block.asText());
            } else if (block.isToolUse()) {
                startBlock = RawContentBlockStartEvent.ContentBlock.ofToolUse(block.asToolUse());
            } else {
                continue;
            }
            events.add(RawMessageStreamEvent.ofContentBlockStart(
                RawContentBlockStartEvent.builder().index(i).contentBlock(startBlock).build()));
            if (block.isText()) {
                events.add(RawMessageStreamEvent.ofContentBlockDelta(
                    RawContentBlockDeltaEvent.builder()
                        .index(i)
                        .delta(RawContentBlockDelta.ofText(
                            TextDelta.builder().text(block.asText().text()).build()))
                        .build()));
            }
            events.add(RawMessageStreamEvent.ofContentBlockStop(
                RawContentBlockStopEvent.builder().index(i).build()));
        }
        events.add(RawMessageStreamEvent.ofMessageDelta(
            RawMessageDeltaEvent.builder()
                .delta(RawMessageDeltaEvent.Delta.builder()
                    .stopReason(message.stopReason().orElse(null))
                    .stopSequence((String) null)
                    .container((Container) null)
                    .build())
                .usage(MessageDeltaUsage.builder()
                    .inputTokens(100).outputTokens(50)
                    .cacheCreationInputTokens(0).cacheReadInputTokens(0)
                    .serverToolUse((ServerToolUsage) null)
                    .build())
                .build()));
        events.add(RawMessageStreamEvent.ofMessageStop(
            RawMessageStopEvent.builder().build()));

        return new StreamResponse<>() {
            @Override
            public Stream<RawMessageStreamEvent> stream() {
                return events.stream();
            }

            @Override
            public void close() {
            }
        };
    }

    public static ToolCallback fileCheckCallback(String filePath) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                    .name("checkFile")
                    .description("Check if a file exists")
                    .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                    .build();
            }

            @Override
            public String call(String toolInput) {
                var file = new File(filePath);
                if (!file.exists()) {
                    throw new AssertionError("File not found: " + filePath);
                }
                return "File exists: " + filePath + " (size: " + file.length() + " bytes)";
            }
        };
    }
}
