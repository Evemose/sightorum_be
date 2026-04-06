package com.rorm.ai.swarm;

import com.rorm.ai.chat.ThinkingLevel;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Objects;

import static org.springframework.util.StringUtils.hasText;

@ConfigurationProperties(prefix = "rorm.ai.swarm")
public record SwarmConfig(
    AgentModelConfig scout,
    AgentModelConfig summarizer,
    AgentModelConfig executor,
    AgentModelConfig planner,
    AgentModelConfig critic,
    AgentModelConfig analyzer
) {

    public SwarmConfig {
        scout = Objects.requireNonNullElseGet(scout, () -> new AgentModelConfig(null, null, null, ThinkingLevel.NONE, null, null));
        summarizer = Objects.requireNonNullElseGet(summarizer, () -> new AgentModelConfig(null, null, null, ThinkingLevel.NONE, null, null));
        executor = Objects.requireNonNullElseGet(executor, () -> new AgentModelConfig(null, null, null, ThinkingLevel.NONE, null, null));
        planner = Objects.requireNonNullElseGet(planner, () -> new AgentModelConfig(null, null, null, ThinkingLevel.NONE, null, null));
        critic = Objects.requireNonNullElseGet(critic, () -> new AgentModelConfig(null, null, null, ThinkingLevel.NONE, null, null));
        analyzer = Objects.requireNonNullElseGet(analyzer, () -> new AgentModelConfig(null, null, null, ThinkingLevel.NONE, null, null));
        if (!hasText(scout.model())) {
            scout = scout.withModel("claude-sonnet-4-6");
        }
        if (!hasText(scout.systemPrompt())) {
            scout = scout.withSystemPrompt(SwarmDefaultPrompts.SCOUT);
        }
        if (scout.thinkingLevel() == ThinkingLevel.NONE) {
            scout = scout.withThinkingLevel(ThinkingLevel.HIGH);
        }
        if (!hasText(summarizer.model())) {
            summarizer = summarizer.withModel("claude-haiku-4-5-20251001");
        }
        if (!hasText(summarizer.systemPrompt())) {
            summarizer = summarizer.withSystemPrompt(SwarmDefaultPrompts.SUMMARIZER);
        }
        if (!hasText(executor.model())) {
            executor = executor.withModel("claude-opus-4-6");
        }
        if (!hasText(executor.systemPrompt())) {
            executor = executor.withSystemPrompt(SwarmDefaultPrompts.EXECUTOR);
        }
        if (executor.thinkingLevel() == ThinkingLevel.NONE) {
            executor = executor.withThinkingLevel(ThinkingLevel.HIGH);
        }
        if (!hasText(planner.model())) {
            planner = planner.withModel("claude-opus-4-6");
        }
        if (!hasText(planner.systemPrompt())) {
            planner = planner.withSystemPrompt(SwarmDefaultPrompts.PLANNER);
        }
        if (planner.thinkingLevel() == ThinkingLevel.NONE) {
            planner = planner.withThinkingLevel(ThinkingLevel.HIGH);
        }
        if (!hasText(critic.model())) {
            critic = critic.withModel("claude-opus-4-6");
        }
        if (!hasText(critic.systemPrompt())) {
            critic = critic.withSystemPrompt(SwarmDefaultPrompts.CRITIC);
        }
        if (critic.thinkingLevel() == ThinkingLevel.NONE) {
            critic = critic.withThinkingLevel(ThinkingLevel.HIGH);
        }
        if (!hasText(analyzer.model())) {
            analyzer = analyzer.withModel("claude-opus-4-6");
        }
        if (!hasText(analyzer.systemPrompt())) {
            analyzer = analyzer.withSystemPrompt(SwarmDefaultPrompts.ANALYZER);
        }
        if (analyzer.thinkingLevel() == ThinkingLevel.NONE) {
            analyzer = analyzer.withThinkingLevel(ThinkingLevel.HIGH);
        }
    }

}
