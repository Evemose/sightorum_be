package com.rorm.ai.swarm;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for each agent in the durable swarm pipeline.
 * <p>
 * Each agent has a system prompt (static, cached) and a user prompt template
 * (per-request, with placeholders). The orchestrator fills placeholders and
 * calls the chat service.
 * <p>
 * The {@link #summarizer} uses a small model to restructure each agent's raw
 * output into a typed DTO (see {@link com.rorm.ai.swarm.agents.SecondarySwarmAgent}).
 */
@ConfigurationProperties(prefix = "rorm.ai.durable-swarm")
public record DurableSwarmConfig(
    AgentModelConfig scout,
    AgentModelConfig domainResearcher,
    AgentModelConfig generator,
    AgentModelConfig mechanicalSceptic,
    AgentModelConfig executorCompiler,
    AgentModelConfig forensicPathologist,
    AgentModelConfig summarizer
) {
}
