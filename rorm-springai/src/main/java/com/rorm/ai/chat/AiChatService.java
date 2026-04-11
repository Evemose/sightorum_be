package com.rorm.ai.chat;

import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;

public interface AiChatService {

    <T> T call(ChatRequest<T> request);

    Flux<String> stream(ChatRequest<?> request);

    /**
     * Stream full {@link ChatResponse} objects including generation metadata.
     * The final response in the flux carries specialized {@link org.springframework.ai.chat.model.Generation}
     * subtypes (thinking, tool rounds, server tools) that callers can pattern-match on.
     */
    Flux<ChatResponse> streamChatResponses(ChatRequest<?> request);

    /**
     * Stream typed {@link StreamToken}s. Consumers pattern-match on variants
     * (Text, Thinking, ToolCall, ServerTool, SearchResult, Citation) instead
     * of parsing string prefixes.
     */
    Flux<StreamToken> streamTokens(ChatRequest<?> request);
}
