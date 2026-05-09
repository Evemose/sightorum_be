package com.rorm.ai.swarm.communication;

import com.rorm.ai.RormToolContext;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;

/**
 * Typed projection of {@link ToolContext} for tools invoked from inside
 * a swarm step. Carries run id, the calling step's chat id (= agent
 * identity, used for self-exclusion), and the calling step's role.
 * <p>
 * {@link #pipelineSpecHolder} is the slot the compiler step installs so
 * {@code validatePipelineSpec} can deposit the validated spec; absent
 * for steps that do not produce a pipeline spec.
 *
 * @param runId              active swarm run identifier
 * @param askerChatId        chat id of the calling step
 * @param askerRole          role of the calling step (e.g. "advocate")
 * @param base               schema/model-space context shared with all tools
 * @param pipelineSpecHolder per-step slot for the compiled pipeline spec,
 *                           populated by {@code validatePipelineSpec} on
 *                           successful validation; null for non-compiler steps
 */
public record SwarmToolContext(
    String runId,
    String askerChatId,
    String askerRole,
    RormToolContext base,
    @Nullable PipelineSpecHolder pipelineSpecHolder
) {

    public static final String RUN_ID_KEY = "swarmRunId";
    public static final String ASKER_CHAT_ID_KEY = "swarmAskerChatId";
    public static final String ASKER_ROLE_KEY = "swarmAskerRole";
    public static final String PIPELINE_SPEC_HOLDER_KEY = "swarmPipelineSpecHolder";

    public static SwarmToolContext from(ToolContext toolContext) {
        var ctx = toolContext.getContext();
        var runId = (String) ctx.get(RUN_ID_KEY);
        var askerChatId = (String) ctx.get(ASKER_CHAT_ID_KEY);
        var askerRole = (String) ctx.get(ASKER_ROLE_KEY);
        if (runId == null || askerChatId == null || askerRole == null) {
            throw new IllegalStateException(
                "Swarm tool invoked outside swarm context: "
                + RUN_ID_KEY + "=" + runId + ", "
                + ASKER_CHAT_ID_KEY + "=" + askerChatId + ", "
                + ASKER_ROLE_KEY + "=" + askerRole);
        }
        return new SwarmToolContext(runId, askerChatId, askerRole,
            RormToolContext.from(toolContext),
            (PipelineSpecHolder) ctx.get(PIPELINE_SPEC_HOLDER_KEY));
    }
}
