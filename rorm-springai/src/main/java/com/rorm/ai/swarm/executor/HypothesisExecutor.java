package com.rorm.ai.swarm.executor;

import com.rorm.ai.swarm.SwarmResult.HypothesisResult;
import com.rorm.ai.swarm.phase.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * JobSpec-callable per-hypothesis executor: runs compile + (optional null) +
 * standoff for one hypothesis. Dispatched as its own Restate sub-invocation by
 * {@link com.rorm.ai.swarm.DurableSwarm}'s hypothesis fanout, so each branch
 * owns its own journal — required because {@link CompilePhase} internally
 * awaits an awakeable (ML pipeline completion) which cannot be journaled
 * inside a parent's {@code ctx.runAsync} closure.
 */
@Component("hypothesisExecutor")
@RequiredArgsConstructor
public class HypothesisExecutor {

    private final CompilePhase compilePhase;
    private final NullPhase nullPhase;
    private final StandoffPhase standoffPhase;

    public HypothesisResult execute(HypothesisExecutionInput input) {
        var hypoCtx = new HypothesisContext(input.anchor(), input.gen(), input.hypothesisTitle());
        var compile = compilePhase.run(hypoCtx, input.runId());
        var pipeCtx = new PipelineContext(hypoCtx, compile);
        var diagnosis = needsNullPhase(compile.pipelineResult().metrics())
            ? nullPhase.run(pipeCtx, input.runId()).diagnosis().dto()
            : null;
        var standoff = standoffPhase.run(pipeCtx, input.runId());
        return new HypothesisResult(
            input.hypothesisTitle(), input.gen().rebuttal().rawResponse(),
            compile.compiler().dto(), compile.pipelineResult(),
            compile.scepticReview().dto(), diagnosis,
            standoff.advocate().dto(), standoff.prosecutor().dto());
    }

    private static boolean needsNullPhase(Map<String, Object> metrics) {
        if (Boolean.TRUE.equals(metrics.get("ci_crosses_zero"))) {
            return true;
        }
        return metrics.get("steps") instanceof Map<?, ?> steps
               && steps.containsKey("null_diagnostics");
    }
}
