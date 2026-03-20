package com.rorm.ai;

import com.rorm.DurableFuture;
import org.springframework.ai.chat.model.ToolContext;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Allows tools to defer their result via a {@link DurableFuture}.
 * <p>
 * The chat model places a mutable {@link #createCollector() collector} in the tool context
 * before tool execution. Tools call {@link #defer} to register their future and return
 * the {@link #PLACEHOLDER}. After all tools execute, the chat model retrieves the collector,
 * {@code DurableFuture.all()}-s the futures, and replaces placeholders with actual results.
 */
public final class DeferredToolResult {

    public static final String PLACEHOLDER = "{\"deferred\":true,\"message\":\"Result pending — will be resolved after all tool calls complete\"}";
    public static final String COLLECTOR_KEY = "_deferredResults";

    private DeferredToolResult() {
    }

    public static Map<String, DurableFuture<String>> createCollector() {
        return new ConcurrentHashMap<>();
    }

    /**
     * Store a deferred future in the collector and return the placeholder.
     * The tool call ID is read from the context ({@code "id"} key set by the chat model).
     */
    @SuppressWarnings("unchecked")
    public static String defer(ToolContext toolContext, DurableFuture<String> future) {
        var id = (String) toolContext.getContext().get("id");
        if (id == null) {
            throw new IllegalStateException("No tool call ID in context — defer must be called from a tool invocation");
        }
        var collector = (Map<String, DurableFuture<String>>) toolContext.getContext().get(COLLECTOR_KEY);
        if (collector == null) {
            throw new IllegalStateException("No deferred result collector in context — chat model must place one before tool execution");
        }
        collector.put(id, future);
        return PLACEHOLDER;
    }
}
