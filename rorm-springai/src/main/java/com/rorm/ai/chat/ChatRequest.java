package com.rorm.ai.chat;

import com.rorm.metamodel.ModelSpace;
import lombok.*;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.advisor.api.Advisor;

import java.util.ArrayList;
import java.util.List;

@With
public record ChatRequest<T>(
    @NonNull String schema,
    @NonNull ModelSpace modelSpace,
    @NonNull String userPrompt,
    @NonNull Class<T> responseType,
    @Nullable String chatId,
    @Nullable String systemPrompt,
    @Nullable String modelName,
    @NonNull ThinkingLevel thinkingLevel,
    @NonNull List<Object> additionalTools,
    @NonNull List<Advisor> additionalAdvisors
) {

    public static Builder usingData(@NonNull String schema, @NonNull ModelSpace modelSpace) {
        return new Builder(schema, modelSpace);
    }

    // TODO: Remove when ML module is refactored to use usingData(schema, modelSpace) directly
    @Deprecated(forRemoval = true)
    public static Builder proceedingOnSchema(@NonNull String schema, @NonNull ChatProgress progress) {
        return new Builder(schema, progress.getModelSpace());
    }

    @With
    @Getter
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    public static class Builder {

        @With(AccessLevel.NONE)
        private final String schema;
        @With(AccessLevel.NONE)
        private final ModelSpace modelSpace;
        private String chatId;
        private ThinkingLevel thinkingLevel = ThinkingLevel.NONE;
        private String systemPrompt;
        private String modelName;
        private List<Object> additionalTools;
        private List<Advisor> additionalAdvisors;

        public ChatRequest<String> ask(String userPrompt) {
            return ask(userPrompt, String.class);
        }

        public <T> ChatRequest<T> ask(String userPrompt, Class<T> responseType) {
            return new ChatRequest<>(
                schema,
                modelSpace,
                userPrompt,
                responseType,
                systemPrompt,
                modelName,
                chatId,
                thinkingLevel,
                additionalTools != null ? additionalTools : List.of(),
                additionalAdvisors != null ? additionalAdvisors : List.of()
            );
        }

        public Builder withTool(Object tool) {
            if (additionalTools == null) {
                additionalTools = List.of(tool);
            } else {
                additionalTools = new ArrayList<>(additionalTools);
                additionalTools.add(tool);
            }
            return this;
        }

        public Builder withAdvisor(Advisor advisor) {
            if (additionalAdvisors == null) {
                additionalAdvisors = List.of(advisor);
            } else {
                additionalAdvisors = new ArrayList<>(additionalAdvisors);
                additionalAdvisors.add(advisor);
            }
            return this;
        }
    }

}
