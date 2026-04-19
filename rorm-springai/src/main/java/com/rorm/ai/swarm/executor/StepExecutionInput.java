package com.rorm.ai.swarm.executor;

import com.rorm.ai.chat.MemoryInclude;
import com.rorm.ai.swarm.EventId;
import com.rorm.metamodel.ModelSpace;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.Set;

/**
 * Serializable input envelope for all step executor beans.
 * Carries per-invocation data across the durable boundary; Spring beans
 * (chat service, event bus, config) are injected into the executor itself.
 *
 * @param eventId            DAG identity for this step (parent links, tags, kind)
 * @param userPrompt         fully-resolved user prompt (no remaining placeholders)
 * @param schema             data schema name
 * @param modelSpace         model space metadata
 * @param runId              shared swarm run identifier for the event bus stream
 * @param chatId             optional conversation ID for multi-turn steps (generator → rebuttal)
 * @param memoryIncludes     optional memory context to include in the chat request
 * @param toolContextEntries extra key-value pairs injected into the ToolContext for this step's tools
 */
public record StepExecutionInput(
    EventId eventId,
    String userPrompt,
    String schema,
    ModelSpace modelSpace,
    String runId,
    @Nullable String chatId,
    @Nullable Set<MemoryInclude> memoryIncludes,
    @Nullable Map<String, Object> toolContextEntries
) {

    public StepExecutionInput(EventId eventId, String userPrompt, String schema,
                              ModelSpace modelSpace, String runId) {
        this(eventId, userPrompt, schema, modelSpace, runId, null, null, null);
    }

    public StepExecutionInput(EventId eventId, String userPrompt, String schema,
                              ModelSpace modelSpace, String runId,
                              @Nullable String chatId, @Nullable Set<MemoryInclude> memoryIncludes) {
        this(eventId, userPrompt, schema, modelSpace, runId, chatId, memoryIncludes, null);
    }
}
