package com.rorm.ai.swarm.communication;

import com.rorm.ai.swarm.EventId;
import com.rorm.ml.dto.RunRecord;
import com.rorm.ml.stream.JobEvent;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-process {@link ToolCallRegistry}. Per-swarm append-only entries
 * mirror the {@link InMemorySwarmContext} pattern; a global
 * {@code byPipelineRunId} index gives unscoped lookups via
 * pipeline-run UUID.
 */
@Component
public class InMemoryToolCallRegistry implements ToolCallRegistry {

    private final ConcurrentMap<String, List<RunRecord>> entriesByRunId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, RunRecord> byPipelineRunId = new ConcurrentHashMap<>();

    @Override
    public void record(String runId, EventId producerEventId, String toolName,
                       @Nullable Object request, JobEvent response) {
        var rec = new RunRecord(toolName, producerEventId, request, response);
        entriesByRunId.computeIfAbsent(runId, _ -> new CopyOnWriteArrayList<>()).add(rec);
        if (response == null) {
            return;
        }
        if (response.jobId() != null) {
            byPipelineRunId.putIfAbsent(response.jobId().toString(), rec);
        }
        // reexecuteCausalPipeline returns metrics.run_id — the pipeline-run id
        // the Python engine mints for the new checkpoint set, distinct from
        // jobId (= Restate analysis_id). The LLM naturally references run_id
        // since it's the meaningful pipeline identifier. Alias-index so
        // registry.find resolves either id to the same record. No collision
        // risk: both jobId and run_id are globally-unique UUIDs from disjoint
        // allocators (Restate server vs. python uuid.uuid4()).
        if (response.metrics().get("run_id") instanceof String alias
            && !alias.isBlank()
            && (response.jobId() == null || !alias.equals(response.jobId().toString()))) {
            byPipelineRunId.putIfAbsent(alias, rec);
        }
    }

    @Override
    public Optional<RunRecord> find(String pipelineRunId) {
        if (pipelineRunId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byPipelineRunId.get(pipelineRunId));
    }

    @Override
    public List<RunRecord> snapshot(String runId) {
        var entries = entriesByRunId.get(runId);
        return entries == null ? List.of() : List.copyOf(entries);
    }
}
