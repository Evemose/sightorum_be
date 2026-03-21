package com.rorm.ai.chat;

import com.rorm.metamodel.ModelSpace;
import lombok.*;
import lombok.experimental.WithBy;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.advisor.api.Advisor;

import java.util.*;

@With
public record ChatRequest<T>(
    @NonNull String schema,
    @NonNull ModelSpace modelSpace,
    @NonNull String userPrompt,
    @NonNull Class<T> responseType,
    @Nullable String chatId,
    @Nullable String sessionId,
    @Nullable String systemPrompt,
    @Nullable String modelName,
    @NonNull ThinkingLevel thinkingLevel,
    @NonNull Set<ToolGroup> toolGroups,
    @NonNull List<Object> additionalTools,
    @NonNull List<Advisor> additionalAdvisors,
    @NonNull Map<String, Object> toolContextEntries,
    @Nullable CacheStrategy cachingStrategyFunction
) {

    public static Builder usingData(@NonNull String schema, @NonNull ModelSpace modelSpace) {
        return new Builder(schema, modelSpace);
    }

    @With
    @WithBy
    @Getter
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    public static class Builder {

        @With(AccessLevel.NONE)
        private final String schema;
        @With(AccessLevel.NONE)
        private final ModelSpace modelSpace;
        private String chatId;
        private String sessionId;
        private ThinkingLevel thinkingLevel = ThinkingLevel.NONE;
        private String systemPrompt;
        private String modelName;
        private Set<ToolGroup> toolGroups;
        private List<Object> additionalTools;
        private List<Advisor> additionalAdvisors;
        private Map<String, Object> toolContextEntries;
        private CacheStrategy cachingStrategyFunction;

        public ChatRequest<String> ask(String userPrompt) {
            return ask(userPrompt, String.class);
        }

        public <T> ChatRequest<T> ask(String userPrompt, Class<T> responseType) {
            return new ChatRequest<>(
                schema,
                modelSpace,
                userPrompt,
                responseType,
                chatId,
                sessionId,
                systemPrompt,
                modelName,
                thinkingLevel,
                toolGroups != null ? toolGroups : Set.of(),
                additionalTools != null ? additionalTools : List.of(),
                additionalAdvisors != null ? additionalAdvisors : List.of(),
                toolContextEntries != null ? toolContextEntries : Map.of(),
                cachingStrategyFunction
            );
        }

        public Builder withToolGroups(ToolGroup... groups) {
            this.toolGroups = EnumSet.copyOf(List.of(groups));
            return this;
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

        public Builder withToolContextEntry(String key, Object value) {
            if (toolContextEntries == null) {
                toolContextEntries = new HashMap<>();
            } else if (!(toolContextEntries instanceof HashMap)) {
                toolContextEntries = new HashMap<>(toolContextEntries);
            }
            toolContextEntries.put(key, value);
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
