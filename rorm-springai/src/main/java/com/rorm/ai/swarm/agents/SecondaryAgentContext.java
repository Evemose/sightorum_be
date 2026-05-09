package com.rorm.ai.swarm.agents;

import com.rorm.ai.swarm.AgentModelConfig;
import com.rorm.ai.swarm.communication.PipelineSpecHolder;
import com.rorm.ai.swarm.executor.StepExecutionInput;
import com.rorm.metamodel.ModelSpace;

/**
 * Inputs available to a {@link SecondarySwarmAgent} after the first-level
 * agent's initial stream completes. Carries the raw text from that
 * stream and a {@link StreamPrimitive} the strategy can call to
 * continue the same conversation thread (same chat id, same advisors,
 * same tool context) with a follow-up user prompt. The same
 * {@link PipelineSpecHolder} the executor installed into the tool
 * context is exposed here so a secondary agent can read whatever
 * {@code validatePipelineSpec} deposited during the stream.
 *
 * @param input              the step's execution envelope
 * @param chatId             conversation id of the first-level agent —
 *                           same id any re-stream uses, so prior turns
 *                           remain in memory and the holder keys align
 * @param raw                accumulated text from the initial stream
 * @param modelSpace         resolved model space for the step's schema
 * @param firstLevelConfig   agent config used by the first-level stream;
 *                           strategies that build their own summarizer
 *                           agent need this to stay consistent
 * @param streamPrimitive    re-stream the first-level agent with a new
 *                           user prompt; tokens flow to the event bus
 *                           and the accumulated raw text is returned
 * @param pipelineSpecHolder the same per-step holder installed in the
 *                           tool context — read it after streaming to
 *                           pick up artifacts deposited by tools
 */
public record SecondaryAgentContext(
    StepExecutionInput input,
    String chatId,
    String raw,
    ModelSpace modelSpace,
    AgentModelConfig firstLevelConfig,
    StreamPrimitive streamPrimitive,
    PipelineSpecHolder pipelineSpecHolder
) {}
