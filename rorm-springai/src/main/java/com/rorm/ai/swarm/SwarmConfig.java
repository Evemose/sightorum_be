package com.rorm.ai.swarm;

import lombok.With;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Objects;

import static org.springframework.util.StringUtils.hasText;

@ConfigurationProperties(prefix = "rorm.ai.swarm")
public record SwarmConfig(
    ModelConfig scout,
    ModelConfig summarizer,
    ModelConfig executor,
    ModelConfig planner,
    ModelConfig critic,
    ModelConfig analyzer
) {

    public SwarmConfig {
        scout = Objects.requireNonNullElseGet(scout, () -> new ModelConfig(null, null));
        summarizer = Objects.requireNonNullElseGet(summarizer, () -> new ModelConfig(null, null));
        executor = Objects.requireNonNullElseGet(executor, () -> new ModelConfig(null, null));
        planner = Objects.requireNonNullElseGet(planner, () -> new ModelConfig(null, null));
        critic = Objects.requireNonNullElseGet(critic, () -> new ModelConfig(null, null));
        analyzer = Objects.requireNonNullElseGet(analyzer, () -> new ModelConfig(null, null));
        if (!hasText(scout.model)) {
            scout = scout.withModel("gpt-5.2-pro-2025-12-11");
        }
        if (!hasText(scout.systemPrompt)) {
            scout = scout.withSystemPrompt(SwarmDefaultPrompts.SCOUT);
        }
        if (!hasText(summarizer.model)) {
            summarizer = summarizer.withModel("gpt-5-nano-2025-08-07");
        }
        if (!hasText(summarizer.systemPrompt)) {
            summarizer = summarizer.withSystemPrompt(SwarmDefaultPrompts.SUMMARIZER);
        }
        if (!hasText(executor.model)) {
            executor = executor.withModel("gpt-5-mini-2025-08-07");
        }
        if (!hasText(executor.systemPrompt)) {
            executor = executor.withSystemPrompt(SwarmDefaultPrompts.EXECUTOR);
        }
        if (!hasText(planner.model)) {
            planner = planner.withModel("gpt-5.2-pro-2025-12-11");
        }
        if (!hasText(planner.systemPrompt)) {
            planner = planner.withSystemPrompt(SwarmDefaultPrompts.PLANNER);
        }
        if (!hasText(critic.model)) {
            critic = critic.withModel("gpt-5.2-pro-2025-12-11");
        }
        if (!hasText(critic.systemPrompt)) {
            critic = critic.withSystemPrompt(SwarmDefaultPrompts.CRITIC);
        }
        if (!hasText(analyzer.model)) {
            analyzer = analyzer.withModel("gpt-5.2-pro-2025-12-11");
        }
        if (!hasText(analyzer.systemPrompt)) {
            analyzer = analyzer.withSystemPrompt(SwarmDefaultPrompts.ANALYZER);
        }
    }

    @With
    public record ModelConfig(
        String model,
        String systemPrompt
    ) {
    }

}
