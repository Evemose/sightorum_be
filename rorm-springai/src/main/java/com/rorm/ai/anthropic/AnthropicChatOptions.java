package com.rorm.ai.anthropic;

import com.rorm.StepJournal;
import com.rorm.ai.chat.ThinkingLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Getter
@Setter
@Builder
public class AnthropicChatOptions implements ToolCallingChatOptions {

    private @Nullable String model;
    private @Nullable Double temperature;
    private @Nullable Integer maxTokens;
    private @Nullable ThinkingLevel thinkingLevel;
    @lombok.Builder.Default
    private StepJournal journal = StepJournal.NOOP;
    @lombok.Builder.Default
    private boolean webAccess = false;
    @lombok.Builder.Default
    private List<ToolCallback> toolCallbacks = List.of();
    @lombok.Builder.Default
    private Set<String> toolNames = Set.of();
    @lombok.Builder.Default
    private Map<String, Object> toolContext = Map.of();
    @lombok.Builder.Default
    private Boolean internalToolExecutionEnabled = false;

    @Override
    public @Nullable Double getFrequencyPenalty() {
        return null;
    }

    @Override
    public @Nullable Double getPresencePenalty() {
        return null;
    }

    @Override
    public @Nullable List<String> getStopSequences() {
        return null;
    }

    @Override
    public @Nullable Integer getTopK() {
        return null;
    }

    @Override
    public @Nullable Double getTopP() {
        return null;
    }

    @Override
    public Boolean getInternalToolExecutionEnabled() {
        return internalToolExecutionEnabled;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T extends ChatOptions> T copy() {
        return (T) AnthropicChatOptions.builder()
            .model(model)
            .temperature(temperature)
            .maxTokens(maxTokens)
            .thinkingLevel(thinkingLevel)
            .journal(journal)
            .webAccess(webAccess)
            .toolCallbacks(List.copyOf(toolCallbacks))
            .toolNames(Set.copyOf(toolNames))
            .toolContext(Map.copyOf(toolContext))
            .internalToolExecutionEnabled(internalToolExecutionEnabled)
            .build();
    }
}
