package com.rorm.ai.swarm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.DurableRuntime;
import com.rorm.JobInvocation;
import com.rorm.JobSpec;
import com.rorm.StepJournal;
import com.rorm.ai.swarm.SwarmResult.AnchorResult;
import com.rorm.ai.swarm.executor.AnchorExecutionInput;
import com.rorm.ai.swarm.phase.JudgePhase;
import com.rorm.ai.swarm.phase.ReconPhase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Orchestrator for the durable swarm workflow. Recon and the judge run in the
 * orchestrator's own invocation; anchor branches are dispatched as parallel
 * sub-invocations of {@link com.rorm.ai.swarm.executor.AnchorExecutor} (which
 * itself fans out hypothesis branches as sub-invocations of
 * {@link com.rorm.ai.swarm.executor.HypothesisExecutor}). Each level of fanout
 * is a real Restate sub-invocation with its own journal — required because the
 * compile phase awaits an awakeable, which cannot be journaled inside a
 * parent's {@code ctx.runAsync} closure.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DurableSwarm {

    private static final Pattern ANCHOR_ENTITY = Pattern.compile("Anchor entity:\\s*(\\S+)");

    private final ReconPhase reconPhase;
    private final JudgePhase judgePhase;
    private final SwarmEventBus eventBus;
    private final DurableRuntime runtime;
    private final ObjectMapper mapper;

    public SwarmResult run(SwarmInput input) {
        return run(input, "swarm-" + StepJournal.current().randomUUID());
    }

    public SwarmResult run(SwarmInput input, String runId) {
        log.info("[swarm] starting run {} with {} anchors", runId, input.anchors().size());
        try {
            var recon = reconPhase.run(input, runId);

            var anchorInvocations = input.anchors().stream()
                .map(anchor -> new JobInvocation(
                    "anchorExecutor-" + runId + "-" + anchorTagOf(anchor),
                    new JobSpec(
                        "anchorExecutor", "execute",
                        new Object[]{new AnchorExecutionInput(
                            input, anchor, anchorTagOf(anchor), recon, runId)},
                        new String[]{AnchorExecutionInput.class.getName()}
                    )))
                .toList();

            var anchorResults = runtime.fanout(anchorInvocations).stream()
                .map(o -> mapper.convertValue(o, AnchorResult.class))
                .toList();

            var judgeVerdict = judgePhase.run(input, recon, anchorResults, runId).dto();
            return new SwarmResult(recon.scout().dto(), recon.domain().dto(), anchorResults, judgeVerdict);
        } finally {
            eventBus.complete(runId);
        }
    }

    static String anchorTagOf(String anchor) {
        var firstLine = anchor.lines().findFirst().orElse("");
        var m = ANCHOR_ENTITY.matcher(firstLine);
        if (m.find()) {
            return m.group(1);
        }
        var trimmed = firstLine.trim();
        return trimmed.isEmpty() ? "anchor" : trimmed;
    }
}
