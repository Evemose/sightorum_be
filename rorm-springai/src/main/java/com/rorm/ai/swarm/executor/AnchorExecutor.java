package com.rorm.ai.swarm.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.DurableRuntime;
import com.rorm.JobInvocation;
import com.rorm.JobSpec;
import com.rorm.ai.swarm.SwarmResult.AnchorResult;
import com.rorm.ai.swarm.SwarmResult.HypothesisResult;
import com.rorm.ai.swarm.phase.AnchorContext;
import com.rorm.ai.swarm.phase.GenPhase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * JobSpec-callable per-anchor executor: runs the gen phase, then fans out the
 * resulting hypotheses as parallel sub-invocations of {@link HypothesisExecutor}.
 * Dispatched as its own Restate sub-invocation by the top-level swarm fanout, so
 * the nested hypothesis fanout's branches see a Restate context with their own
 * journal.
 */
@Component("anchorExecutor")
@RequiredArgsConstructor
public class AnchorExecutor {

    private final GenPhase genPhase;
    private final DurableRuntime runtime;
    private final ObjectMapper mapper;

    public AnchorResult execute(AnchorExecutionInput input) {
        var anchorCtx = new AnchorContext(input.swarm(), input.anchor(), input.anchorTag(), input.recon());
        var gen = genPhase.run(anchorCtx, input.runId());

        var hypothesisInvocations = gen.rebuttal().dto().hypotheses().stream()
            .map(h -> new JobInvocation(
                "hypothesisExecutor-" + input.anchorTag() + "-" + h.title(),
                new JobSpec(
                    "hypothesisExecutor", "execute",
                    new Object[]{new HypothesisExecutionInput(
                        anchorCtx, gen, h.title(), input.runId())},
                    new String[]{HypothesisExecutionInput.class.getName()}
                )))
            .toList();

        var hypothesisResults = runtime.fanout(hypothesisInvocations).stream()
            .map(o -> mapper.convertValue(o, HypothesisResult.class))
            .toList();

        return new AnchorResult(input.anchor(), gen.chatId(),
            gen.generator().dto(), gen.sceptic().dto(), gen.rebuttal().dto(),
            hypothesisResults);
    }
}
