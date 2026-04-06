package com.rorm.ai.swarm;

import com.rorm.ai.chat.CacheStrategy;
import com.rorm.ai.chat.ThinkingLevel;
import com.rorm.ai.chat.ToolGroup;
import lombok.With;
import org.jspecify.annotations.Nullable;

import java.util.Set;

/**
 * Unified configuration for any swarm agent.
 * <p>
 * {@link #systemPrompt} defines the agent's role (static, cached).
 * {@link #userPromptTemplate} is the per-request input template with
 * {@code {{PLACEHOLDER}}} markers filled by the orchestrator.
 */
@With
public record AgentModelConfig(
    String model,
    String systemPrompt,
    String userPromptTemplate,
    ThinkingLevel thinkingLevel,
    @Nullable Set<ToolGroup> toolGroups,
    @Nullable CacheStrategy cacheStrategy
) {
}
