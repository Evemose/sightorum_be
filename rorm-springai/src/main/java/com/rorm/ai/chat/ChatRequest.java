package com.rorm.ai.chat;

import com.rorm.metamodel.ModelSpace;
import lombok.*;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Builder
public record ChatRequest<T>(
    @NonNull String schema,
    @NonNull ChatProgress progress,
    @NonNull String prompt,
    @NonNull Class<T> responseType,
    @Nullable String chatId,
    @NonNull ThinkingLevel thinkingLevel
) {

    public static Builder usingData(@NonNull String schema, @NonNull ModelSpace modelSpace) {
        return new Builder(schema, new ChatProgress(modelSpace));
    }

    public static Builder proceedingOnSchema(@NonNull String schema, ChatProgress progress) {
        return new Builder(schema, progress);
    }

    @With
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    public static class Builder {

        @With(AccessLevel.NONE)
        private final String schema;
        @With(AccessLevel.NONE)
        private final ChatProgress progress;
        private String chatId;
        private ThinkingLevel thinkingLevel = ThinkingLevel.NONE;

        public ChatRequest<String> ask(String prompt) {
            return new ChatRequest<>(schema, progress, prompt, String.class, chatId, thinkingLevel);
        }

        public <T> ChatRequest<T> ask(String prompt, Class<T> responseType) {
            return new ChatRequest<>(schema, progress, prompt, responseType, chatId, thinkingLevel);
        }

    }

}
