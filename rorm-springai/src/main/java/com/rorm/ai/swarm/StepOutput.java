package com.rorm.ai.swarm;

/**
 * Result of a single durable step: carries the step's {@link EventId},
 * the structured DTO produced by the summarizer, and the raw agent
 * response text. Tool-call records ({@code executePipeline} /
 * {@code reexecuteCausalPipeline} runs) are not in here — they live in
 * the swarm-wide
 * {@link com.rorm.ai.swarm.communication.ToolCallRegistry} keyed by
 * {@code runId}; consumers query that registry by pipeline run id or
 * snapshot per swarm.
 *
 * @param <T> DTO type produced by this step
 * @param id          the step's content-addressed event id
 * @param dto         typed structured output produced by the step
 * @param rawResponse accumulated raw text from the first-level agent
 */
public record StepOutput<T>(EventId id, T dto, String rawResponse) {
}
