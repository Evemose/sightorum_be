package com.rorm.ai.anthropic;

import com.anthropic.models.messages.Message;

public record TokenUsage(
    long inputTokens,
    long outputTokens,
    long cacheCreationTokens,
    long cacheReadTokens,
    String model
) implements UsageConsuming {

    public static TokenUsage from(Message message) {
        var u = message.usage();
        return new TokenUsage(
            u.inputTokens(),
            u.outputTokens(),
            u.cacheCreationInputTokens().orElse(0L),
            u.cacheReadInputTokens().orElse(0L),
            message.model().toString()
        );
    }

    public static UsageConsuming estimate(long inputTokens, String model) {
        return new TokenUsage(inputTokens, 0, 0, 0, model);
    }
}
