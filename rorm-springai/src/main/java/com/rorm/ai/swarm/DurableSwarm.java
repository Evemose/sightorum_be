package com.rorm.ai.swarm;

import com.rorm.StepJournal;
import com.rorm.ai.swarm.SwarmResult.AnchorResult;
import com.rorm.ai.swarm.SwarmResult.HypothesisResult;
import com.rorm.ai.swarm.dto.HypothesisGenerationDTO.Hypothesis;
import com.rorm.ai.swarm.phase.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Orchestrator for the durable swarm workflow. Each phase is a self-contained
 * component that submits its individual steps as independent durable
 * invocations via {@link com.rorm.DurableRuntime}. {@code runId} is threaded
 * explicitly into phase public APIs (so it survives the fanout thread
 * transition) — each phase rebinds it into {@link PhaseScope} internally.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DurableSwarm {

    private static final Pattern ANCHOR_ENTITY = Pattern.compile("Anchor entity:\\s*(\\S+)");

    private final ReconPhase reconPhase;
    private final GenPhase genPhase;
    private final CompilePhase compilePhase;
    private final NullPhase nullPhase;
    private final StandoffPhase standoffPhase;
    private final JudgePhase judgePhase;
    private final SwarmEventBus eventBus;

    public SwarmResult run(SwarmInput input) {
        return run(input, "swarm-" + StepJournal.current().randomUUID());
    }

    public SwarmResult run(SwarmInput input, String runId) {
        log.info("[swarm] starting run {} with {} anchors", runId, input.anchors().size());
        try {
            var recon = reconPhase.run(input, runId);
            var anchorResults = StepJournal.current().fanout("swarm:anchor", AnchorResult.class,
                input.anchors().stream()
                    .<Supplier<AnchorResult>>map(anchor -> () -> runAnchor(input, anchor, recon, runId))
                    .toList());
            var judgeVerdict = judgePhase.run(input, recon, anchorResults, runId).dto();
            return new SwarmResult(recon.scout().dto(), recon.domain().dto(), anchorResults, judgeVerdict);
        } finally {
            eventBus.complete(runId);
        }
    }

    private AnchorResult runAnchor(SwarmInput input, String anchor, ReconPhase.Output recon, String runId) {
        var anchorCtx = new AnchorContext(input, anchor, anchorTagOf(anchor), recon);
        var gen = genPhase.run(anchorCtx, runId);
        log.info("[swarm] {} hypotheses for anchor {}",
            gen.rebuttal().dto().hypotheses().size(), anchorCtx.anchorTag());
        var hypothesisResults = StepJournal.current().fanout("swarm:hypothesis", HypothesisResult.class,
            gen.rebuttal().dto().hypotheses().stream()
                .<Supplier<HypothesisResult>>map(h -> () -> runHypothesis(anchorCtx, gen, h, runId))
                .toList());
        return new AnchorResult(anchor, gen.chatId(), gen.generator().dto(),
            gen.sceptic().dto(), gen.rebuttal().dto(), hypothesisResults);
    }

    private HypothesisResult runHypothesis(AnchorContext anchorCtx, GenPhase.Output gen,
                                           Hypothesis h, String runId) {
        var hypoCtx = new HypothesisContext(anchorCtx, gen, h.title());
        var compile = compilePhase.run(hypoCtx, runId);
        var pipeCtx = new PipelineContext(hypoCtx, compile);
        var diagnosis = needsNullPhase(compile.pipelineResult().metrics())
            ? nullPhase.run(pipeCtx, runId).diagnosis().dto()
            : null;
        var standoff = standoffPhase.run(pipeCtx, runId);
        return new HypothesisResult(h.title(), gen.rebuttal().rawResponse(),
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
