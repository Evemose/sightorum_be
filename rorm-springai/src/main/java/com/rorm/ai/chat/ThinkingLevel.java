package com.rorm.ai.chat;

/**
 * Controls the reasoning effort level for AI responses.
 * Higher levels may produce more thorough analysis but take longer and cost more.
 */
public enum ThinkingLevel {
    /**
     * No extended thinking - standard response generation.
     */
    NONE(null),

    /**
     * Medium reasoning effort - balanced between speed and depth.
     */
    MEDIUM("medium"),

    /**
     * High reasoning effort - thorough analysis with detailed reasoning.
     */
    HIGH("high");

    private final String apiValue;

    ThinkingLevel(String apiValue) {
        this.apiValue = apiValue;
    }

    /**
     * @return The API value to pass to OpenAI, or null if no reasoning effort should be set.
     */
    String apiValue() {
        return apiValue;
    }

    /**
     * @return true if this level requires extended thinking options to be set.
     */
    boolean requiresOptions() {
        return apiValue != null;
    }
}
