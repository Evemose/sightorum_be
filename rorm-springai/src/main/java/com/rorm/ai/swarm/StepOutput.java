package com.rorm.ai.swarm;

/**
 * Result of a single durable step: carries the step's {@link EventId}, the
 * structured DTO produced by the summarizer, and the raw agent response text.
 * Serializable envelope for passing step results between phases and across
 * durable invocation boundaries.
 *
 * @param <T> DTO type produced by this step
 */
public record StepOutput<T>(EventId id, T dto, String rawResponse) {}
