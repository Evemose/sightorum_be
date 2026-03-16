package com.rorm.ai.chat;

import reactor.core.publisher.Flux;

public interface AiChatService {

    <T> T call(ChatRequest<T> request);

    Flux<String> stream(ChatRequest<?> request);
}
