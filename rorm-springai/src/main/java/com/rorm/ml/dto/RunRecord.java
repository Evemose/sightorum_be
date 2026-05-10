package com.rorm.ml.dto;

import com.rorm.ai.swarm.EventId;
import com.rorm.ml.stream.JobEvent;
import org.jspecify.annotations.Nullable;

/**
 * One pipeline run an agent submitted via a tool call.
 * <p>
 * {@code producerEventId} is the {@link EventId} of the agent
 * invocation that produced the run — its {@code kind()} identifies the
 * agent role ({@code "compiler"}, {@code "compiler-sceptic"}, etc.),
 * its {@code token()} disambiguates across iterations and hypotheses,
 * and its {@code tags()} / {@code parents()} let consumers walk the
 * provenance chain. Because {@code producerEventId} is structural
 * agent attribution, a single accumulating registry survives any
 * scoping (per-hypothesis or wider): consumers filter / group by
 * {@code producerEventId.kind()}, {@code .token()}, or {@code .tags()}
 * as needed.
 *
 * @param toolName        the tool that produced the run
 *                        ({@code "executePipeline"} /
 *                        {@code "reexecuteCausalPipeline"})
 * @param producerEventId the agent invocation that ran the tool
 * @param request         payload the agent supplied —
 *                        {@link PipelineSpecRequest} for
 *                        {@code executePipeline}, {@link ReexecuteRequest}
 *                        for {@code reexecuteCausalPipeline}
 * @param response        resolved {@link JobEvent}
 */
public record RunRecord(
    String toolName,
    EventId producerEventId,
    @Nullable Object request,
    JobEvent response
) {

    /**
     * Request payload for {@code reexecuteCausalPipeline}.
     */
    public record ReexecuteRequest(String baseRunId, PipelineSpecPatch patch) {}
}
