package com.rorm.ai.swarm.executor;

import com.rorm.ai.swarm.EventId;
import com.rorm.ai.swarm.StepOutput;
import com.rorm.ai.swarm.SwarmResult.HypothesisResult;
import com.rorm.ai.swarm.dto.HypothesisGenerationDTO;
import com.rorm.ai.swarm.dto.SupervisorVerdictDTO;
import com.rorm.ai.swarm.phase.*;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.*;

import static com.rorm.ai.swarm.phase.IterationHistoryRenderer.append;

/**
 * JobSpec-callable per-hypothesis executor: runs the generator-compiler-sceptic
 * trio under a supervisor that decides whether to pass through, send a follow-up
 * to the compiler in its persistent chat (LOOP_TO_COMPILER), or re-run the
 * generator (LOOP_TO_GENERATOR). After the supervised loop terminates, runs the
 * optional null phase and the standoff phase.
 */
@Component("hypothesisExecutor")
@RequiredArgsConstructor
public class HypothesisExecutor {

    private static final int MAX_ITERATIONS = 5;

    private final GenPhase genPhase;
    private final CompilePhase compilePhase;
    private final SupervisorPhase supervisorPhase;
    private final NullPhase nullPhase;
    private final StandoffPhase standoffPhase;
    private final com.rorm.ai.swarm.communication.ToolCallRegistry toolCallRegistry;

    public HypothesisResult execute(HypothesisExecutionInput input) {
        var supervisorChatId = supervisorChatIdFor(input);
        var outcome = runSupervisedLoop(input, supervisorChatId);
        return finalizeHypothesis(input, outcome);
    }

    private static String supervisorChatIdFor(HypothesisExecutionInput input) {
        return "swarm-supervisor-" + input.anchor().anchorTag() + "-" + input.hypothesisTitle();
    }

    private LoopOutcome runSupervisedLoop(HypothesisExecutionInput input, String supervisorChatId) {
        var hypoCtx = new HypothesisContext(input.anchor(), input.gen(), input.hypothesisTitle());
        var compile = compilePhase.run(hypoCtx, input.runId());
        SupervisorVerdictDTO verdict = null;
        for (int iter = 0; iter < MAX_ITERATIONS; iter++) {
            var supervisorOutput = supervisorPhase.run(
                new SupervisorPhase.Input(hypoCtx, compile, supervisorChatId, iter, MAX_ITERATIONS),
                input.runId());
            verdict = supervisorOutput.dto();
            if (terminates(verdict, iter)) {
                break;
            }
            var step = applyLoopVerdict(new LoopParams(
                hypoCtx, compile, verdict, supervisorOutput.id(), iter, input.runId()));
            hypoCtx = step.hypoCtx();
            compile = step.compile();
        }
        return new LoopOutcome(hypoCtx, compile, verdict);
    }

    private LoopStep applyLoopVerdict(LoopParams params) {
        return switch (params.verdict().decision()) {
            case LOOP_TO_COMPILER -> applyCompilerLoop(params);
            case LOOP_TO_GENERATOR -> applyGeneratorLoop(params);
            case PASS_THROUGH -> throw new IllegalStateException(
                "PASS_THROUGH must be handled by terminates() before applyLoopVerdict");
        };
    }

    private static boolean terminates(SupervisorVerdictDTO verdict, int iter) {
        return verdict.decision() == SupervisorVerdictDTO.Decision.PASS_THROUGH
               || iter + 1 >= MAX_ITERATIONS;
    }

    private LoopStep applyCompilerLoop(LoopParams params) {
        var verdict = params.verdict();
        requireCompilerFocus(verdict);
        var compile = compilePhase.continueCompiler(
            new CompilePhase.ContinueInput(params.hypoCtx(), params.compile(),
                verdict.compilerFocus(), params.iter() + 1, params.supervisorId()),
            params.runId());
        return new LoopStep(params.hypoCtx(), compile);
    }

    private static boolean isDead(SupervisorVerdictDTO verdict) {
        return verdict != null
               && verdict.decision() == SupervisorVerdictDTO.Decision.PASS_THROUGH
               && verdict.passReason() == SupervisorVerdictDTO.PassThroughReason.HYPOTHESIS_DEAD;
    }

    private LoopStep applyGeneratorLoop(LoopParams params) {
        var verdict = params.verdict();
        requireRefinementRequest(verdict);
        var iteration = params.iter() + 1;
        var refined = genPhase.runSupervisorRefinement(
            new GenPhase.RefinementInput(params.hypoCtx(),
                verdict.refinementRequest(), iteration, params.supervisorId()),
            params.runId());
        var newCtx = withRefinedRebuttal(params.hypoCtx(), refined, iteration);
        var newCompile = compilePhase.run(newCtx, params.runId());
        var mergedCompile = mergeCompileHistory(params.compile(), newCompile, iteration);
        return new LoopStep(newCtx, mergedCompile);
    }

    private static void requireCompilerFocus(SupervisorVerdictDTO verdict) {
        if (verdict.compilerFocus() == null || verdict.compilerFocus().isBlank()) {
            throw new IllegalStateException(
                "Supervisor returned LOOP_TO_COMPILER without compilerFocus: " + verdict.explanation());
        }
    }

    private static HypothesisContext withRefinedRebuttal(
        HypothesisContext prior, StepOutput<HypothesisGenerationDTO> refined, int iteration) {
        var prevGen = prior.gen();
        var combined = new StepOutput<>(refined.id(), refined.dto(),
            append(prevGen.rebuttal().rawResponse(), refined.rawResponse(),
                "supervisor refinement", iteration));
        var newGen = new GenPhase.Output(
            prevGen.chatId(), prevGen.generator(), prevGen.sceptic(), combined);
        return new HypothesisContext(prior.anchor(), newGen, prior.hypothesisId());
    }

    private static CompilePhase.Output mergeCompileHistory(
        CompilePhase.Output prior, CompilePhase.Output next, int iteration) {
        var compiler = new StepOutput<>(next.compiler().id(), next.compiler().dto(),
            append(prior.compiler().rawResponse(), next.compiler().rawResponse(),
                "compiler reload", iteration));
        var scepticReview = new StepOutput<>(next.scepticReview().id(), next.scepticReview().dto(),
            append(prior.scepticReview().rawResponse(), next.scepticReview().rawResponse(),
                "compiler-sceptic reload", iteration));
        return new CompilePhase.Output(compiler, scepticReview, next.scepticChatId());
    }

    private HypothesisResult finalizeHypothesis(HypothesisExecutionInput input, LoopOutcome outcome) {
        var pipeCtx = new PipelineContext(outcome.hypoCtx(), outcome.compile());
        var verdict = outcome.supervisorVerdict();
        var dead = isDead(verdict);
        var compile = outcome.compile();
        var allCalls = toolCallRegistry.snapshot(input.runId());
        var compilerTokens = collectAncestorTokensOfKind(compile.compiler().id(), "compiler");
        var scepticTokens = collectAncestorTokensOfKind(compile.scepticReview().id(), "compiler-sceptic");
        var compilerRuns = allCalls.stream()
            .filter(r -> compilerTokens.contains(r.producerEventId().token())).toList();
        var scepticRuns = allCalls.stream()
            .filter(r -> scepticTokens.contains(r.producerEventId().token())).toList();
        var representativeMetrics = representativeMetrics(scepticRuns);
        var wantsDiagnosis = dead || (representativeMetrics != null && needsNullPhase(representativeMetrics));
        var diagnosis = wantsDiagnosis ? nullPhase.run(pipeCtx, input.runId()).diagnosis().dto() : null;
        var standoff = dead ? null : standoffPhase.run(pipeCtx, input.runId());
        return new HypothesisResult(
            input.hypothesisTitle(), outcome.hypoCtx().gen().rebuttal().rawResponse(),
            compile.compiler().rawResponse(), compile.compiler().dto(),
            compilerRuns,
            compile.scepticReview().rawResponse(),
            scepticRuns,
            compile.scepticReview().dto(), diagnosis,
            standoff != null ? standoff.advocate().dto() : null,
            standoff != null ? standoff.prosecutor().dto() : null,
            verdict);
    }

    private static Set<UUID> collectAncestorTokensOfKind(
        EventId start, String kind
    ) {
        var result = new HashSet<UUID>();
        var queue = new ArrayDeque<EventId>();
        queue.add(start);
        while (!queue.isEmpty()) {
            var id = queue.poll();
            if (kind.equals(id.kind())) {
                result.add(id.token());
            }
            queue.addAll(id.parents());
        }
        return result;
    }

    private static void requireRefinementRequest(SupervisorVerdictDTO verdict) {
        if (verdict.refinementRequest() == null || verdict.refinementRequest().isBlank()) {
            throw new IllegalStateException(
                "Supervisor returned LOOP_TO_GENERATOR without refinementRequest: " + verdict.explanation());
        }
    }

    private static @Nullable Map<String, Object> representativeMetrics(
        java.util.List<com.rorm.ml.dto.RunRecord> scepticRuns
    ) {
        if (scepticRuns.isEmpty()) {
            return null;
        }
        var event = scepticRuns.getLast().response();
        return event != null ? event.metrics() : null;
    }

    private static boolean needsNullPhase(Map<String, Object> metrics) {
        if (Boolean.TRUE.equals(metrics.get("ci_crosses_zero"))) {
            return true;
        }
        return metrics.get("steps") instanceof Map<?, ?> steps
               && steps.containsKey("null_diagnostics");
    }

    private record LoopOutcome(
        HypothesisContext hypoCtx,
        CompilePhase.Output compile,
        SupervisorVerdictDTO supervisorVerdict
    ) {}

    private record LoopStep(HypothesisContext hypoCtx, CompilePhase.Output compile) {}

    private record LoopParams(
        HypothesisContext hypoCtx,
        CompilePhase.Output compile,
        SupervisorVerdictDTO verdict,
        EventId supervisorId,
        int iter,
        String runId
    ) {}
}
