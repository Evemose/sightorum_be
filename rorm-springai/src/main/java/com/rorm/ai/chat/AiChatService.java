package com.rorm.ai.chat;

import lombok.SneakyThrows;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import reactor.core.publisher.Flux;

public interface AiChatService {
    @SneakyThrows
    @Retryable(backoff = @Backoff(delay = 0))
    <T> T call(ChatRequest<T> request);

    Flux<String> stream(ChatRequest<?> request);
}
