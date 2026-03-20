package com.rorm.ai.anthropic;

public interface UsageConsuming {

    String model();

    default long totalTokens() {
        return inputTokens() + outputTokens();
    }

    long inputTokens();

    long outputTokens();

    default long totalTokensIncludingCache() {
        return inputTokens() + outputTokens() + cacheCreationTokens() + cacheReadTokens();
    }

    long cacheCreationTokens();

    long cacheReadTokens();
}
