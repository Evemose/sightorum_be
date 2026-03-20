package com.rorm.ai.chat;

/**
 * Controls the reasoning effort level for AI responses.
 * Higher levels may produce more thorough analysis but take longer and cost more.
 */
public enum ThinkingLevel {
    /**
     * No extended thinking - standard response generation.
     */
    NONE,

    /**
     * Medium reasoning effort - balanced between speed and depth.
     */
    MEDIUM,

    /**
     * High reasoning effort - thorough analysis with detailed reasoning.
     */
    HIGH
}
