package com.rorm.ai.swarm.agents;

/**
 * Outcome of {@link SecondarySwarmAgent#produce(SecondaryAgentContext)}:
 * the typed DTO and the raw text to attach to
 * {@link com.rorm.ai.swarm.StepOutput#rawResponse()}. Implementations
 * that re-stream return the full accumulated text (initial response
 * plus every iteration) so downstream consumers see the complete
 * interaction trace.
 */
public record SecondaryAgentResult<T>(T dto, String raw) {}
