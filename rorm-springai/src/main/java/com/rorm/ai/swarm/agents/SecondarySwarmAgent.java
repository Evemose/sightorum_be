package com.rorm.ai.swarm.agents;

/**
 * Strategy that turns a first-level agent's raw streamed output into the
 * step's structured DTO. The default {@link SummarizingSecondaryAgent}
 * runs a Haiku-class summarizer over the raw text; specialized
 * implementations may bypass summarization entirely (e.g. the compiler
 * step reads the validated {@code PipelineSpecRequest} from the
 * tool-context holder populated by {@code validatePipelineSpec}).
 *
 * @param <T> typed DTO this secondary agent produces
 */
public interface SecondarySwarmAgent<T> {

    /**
     * Produce the typed DTO and the raw text to attach to the
     * {@link com.rorm.ai.swarm.StepOutput}. Implementations may re-stream
     * the first-level agent through the
     * {@link SecondaryAgentContext#streamPrimitive() stream primitive}
     * if the initial output did not satisfy the step's contract; the raw
     * field of the returned result should reflect the full
     * accumulated text across all iterations.
     */
    SecondaryAgentResult<T> produce(SecondaryAgentContext context);
}
