package com.rorm.ai.swarm.communication;

import com.rorm.ai.RormToolContext;
import com.rorm.ai.swarm.EventId;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;

/**
 * Typed projection of {@link ToolContext} for tools invoked from inside
 * a swarm step. Carries the run id, the calling step's full
 * {@link EventId} (used both for self-exclusion and as the
 * {@code producerEventId} attribution on tool-call records), and the
 * calling step's chat id.
 *
 * @param runId              active swarm run identifier
 * @param askerEventId       full event id of the calling step — its
 *                           {@code kind()} is the role, its
 *                           {@code token()} disambiguates iterations,
 *                           and its {@code parents()}/{@code tags()}
 *                           give provenance
 * @param askerChatId        chat id of the calling step
 * @param base               schema/model-space context shared with all tools
 * @param pipelineSpecHolder per-step slot for the compiled pipeline spec,
 *                           populated by {@code validatePipelineSpec} on
 *                           successful validation; null for non-compiler steps
 * @param toolCallRegistry   shared swarm-wide registry; tools record their
 *                           runs into it tagged with {@code askerEventId}
 *                           and resolve upstream runs through {@link
 *                           ToolCallRegistry#find(String, String)}
 */
public record SwarmToolContext(
    String runId,
    EventId askerEventId,
    String askerChatId,
    RormToolContext base,
    @Nullable PipelineSpecHolder pipelineSpecHolder,
    @Nullable ToolCallRegistry toolCallRegistry
) {

    public static final String RUN_ID_KEY = "swarmRunId";
    public static final String ASKER_EVENT_ID_KEY = "swarmAskerEventId";
    public static final String ASKER_CHAT_ID_KEY = "swarmAskerChatId";
    public static final String PIPELINE_SPEC_HOLDER_KEY = "swarmPipelineSpecHolder";
    public static final String TOOL_CALL_REGISTRY_KEY = "swarmToolCallRegistry";

    public static SwarmToolContext from(ToolContext toolContext) {
        var ctx = toolContext.getContext();
        var runId = (String) ctx.get(RUN_ID_KEY);
        var askerEventId = (EventId) ctx.get(ASKER_EVENT_ID_KEY);
        var askerChatId = (String) ctx.get(ASKER_CHAT_ID_KEY);
        if (runId == null || askerEventId == null || askerChatId == null) {
            throw new IllegalStateException(
                "Swarm tool invoked outside swarm context: "
                + RUN_ID_KEY + "=" + runId + ", "
                + ASKER_EVENT_ID_KEY + "=" + askerEventId + ", "
                + ASKER_CHAT_ID_KEY + "=" + askerChatId);
        }
        return new SwarmToolContext(runId, askerEventId, askerChatId,
            RormToolContext.from(toolContext),
            (PipelineSpecHolder) ctx.get(PIPELINE_SPEC_HOLDER_KEY),
            (ToolCallRegistry) ctx.get(TOOL_CALL_REGISTRY_KEY));
    }

    public String askerRole() {
        return askerEventId.kind();
    }
}
