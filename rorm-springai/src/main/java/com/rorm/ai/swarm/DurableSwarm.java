package com.rorm.ai.swarm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.DurableRuntime;
import com.rorm.JobInvocation;
import com.rorm.JobSpec;
import com.rorm.StepJournal;
import com.rorm.ai.swarm.SwarmResult.AnchorResult;
import com.rorm.ai.swarm.dto.ProposedAnchor;
import com.rorm.ai.swarm.executor.AnchorExecutionInput;
import com.rorm.ai.swarm.phase.JudgePhase;
import com.rorm.ai.swarm.phase.ReconPhase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
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
        var explicit = input.anchors() == null ? 0 : input.anchors().size();
        log.info("[swarm] starting run {} with {} explicit anchor(s)", runId, explicit);
        var recon = reconPhase.run(input, runId);
        var anchors = effectiveAnchors(input, recon);
        var anchorResults = runAnchors(input, anchors, recon, runId);
        var judgeVerdict = judgePhase.run(input, recon, anchorResults, runId).dto();
        var result = new SwarmResult(recon.scout().dto(), recon.domain().dto(),
            anchorResults, judgeVerdict);
        eventBus.complete(runId);
        return result;
    }

    private static List<String> effectiveAnchors(SwarmInput input, ReconPhase.Output recon) {
        if (input.anchors() != null && !input.anchors().isEmpty()) {
            return input.anchors();
        }
        var merged = AnchorReconciler.merge(
            recon.scout().dto().proposedAnchors(),
            recon.domain().dto().proposedAnchors());
        if (merged.isEmpty()) {
            throw new IllegalStateException(
                "No anchors supplied and neither the scout nor the domain researcher "
                + "proposed any — cannot fan out hypotheses.");
        }
        log.info("[swarm] derived {} anchor(s) from scout + domain proposals", merged.size());
        return merged.stream().map(DurableSwarm::formatAnchor).toList();
    }

    private List<AnchorResult> runAnchors(SwarmInput input, List<String> anchors,
                                          ReconPhase.Output recon, String runId) {
        var invocations = anchors.stream()
            .map(anchor -> new JobInvocation(
                "anchorExecutor-" + runId + "-" + anchorTagOf(anchor),
                new JobSpec(
                    "anchorExecutor", "execute",
                    new Object[]{new AnchorExecutionInput(
                        input, anchor, anchorTagOf(anchor), recon, runId)},
                    new String[]{AnchorExecutionInput.class.getName()}
                )))
            .toList();
        return runtime.fanout(invocations).stream()
            .map(o -> mapper.convertValue(o, AnchorResult.class))
            .toList();
    }

    private static String formatAnchor(ProposedAnchor anchor) {
        return "Anchor entity: " + slugify(anchor.name())
               + "\nName: " + anchor.name()
               + "\nEntities: " + String.join(", ", anchor.entities())
               + "\nPerspective: " + anchor.perspective()
               + "\nRationale: " + anchor.rationale()
               + "\nSource: " + (anchor.source() == null ? "unknown" : anchor.source());
    }

    private static String slugify(String name) {
        var slug = name.toLowerCase().replaceAll("[^a-z0-9]+", "_");
        return slug.replaceAll("^_+|_+$", "");
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
