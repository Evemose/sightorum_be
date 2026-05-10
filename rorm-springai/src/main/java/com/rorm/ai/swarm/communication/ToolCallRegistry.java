package com.rorm.ai.swarm.communication;

import com.rorm.ai.swarm.EventId;
import com.rorm.ml.dto.RunRecord;
import com.rorm.ml.stream.JobEvent;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * Swarm-wide accumulating record of pipeline runs every agent submitted
 * via tool calls ({@code executePipeline},
 * {@code reexecuteCausalPipeline}). One singleton per Spring context;
 * each record is stored under the swarm {@code runId} that produced it
 * so concurrent swarms stay isolated for snapshots while still sharing
 * a globally-unique pipeline-run-UUID index for direct lookups.
 * <p>
 * Each {@link RunRecord} carries the agent's full {@link EventId} as
 * {@code producerEventId} (kind + token + parents + tags); downstream
 * consumers filter by walking the parent DAG from a known terminal
 * event id (e.g. the latest compiler / sceptic step), which selects
 * only records produced under that subtree.
 */
public interface ToolCallRegistry {

    /**
     * Records a tool-call attributed to {@code producerEventId} under {@code runId}.
     */
    void record(String runId, EventId producerEventId, String toolName,
                @Nullable Object request, JobEvent response);

    /**
     * Resolves a record by its pipeline run id. Pipeline run UUIDs are
     * globally unique, so the lookup is unscoped — there is no need to
     * also pass the swarm {@code runId}.
     */
    Optional<RunRecord> find(String pipelineRunId);

    /**
     * Every record submitted under {@code runId}, in submission order.
     */
    List<RunRecord> snapshot(String runId);
}
