package com.rorm.ai.swarm.executor;

import com.rorm.ai.swarm.StepOutput;
import com.rorm.ai.swarm.SwarmResult.HypothesisResult;
import com.rorm.ai.swarm.dto.HypothesisGenerationDTO;
import com.rorm.ai.swarm.dto.SupervisorVerdictDTO;
import com.rorm.ai.swarm.phase.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * JobSpec-callable per-hypothesis executor: runs the generator-compiler-sceptic
 * trio under a supervisor that decides whether to pass through, send a follow-up
 * user message to the compiler-sceptic in its persistent chat, or send a
 * follow-up user message to the generator in its persistent chat. After the
 * supervised loop terminates, runs the optional null phase and the standoff
 * phase. Dispatched as its own Restate sub-invocation by
 * {@link com.rorm.ai.swarm.DurableSwarm}'s hypothesis fanout so each branch owns
 * its own journal — required because {@link CompilePhase} internally awaits an
 * awakeable (ML pipeline completion) which cannot be journaled inside a
 * parent's {@code ctx.runAsync} closure.
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

    public HypothesisResult execute(HypothesisExecutionInput input) {
        var supervisorChatId = supervisorChatIdFor(input);
        var outcome = runSupervisedLoop(input, supervisorChatId);
        return finalizeHypothesis(input, outcome);
    }

    private static String supervisorChatIdFor(HypothesisExecutionInput input) {
        return "swarm-supervisor-" + input.gen().rebuttal().id().token() + "-" + input.hypothesisTitle();
    }

    private LoopOutcome runSupervisedLoop(HypothesisExecutionInput input, String supervisorChatId) {
        var hypoCtx = new HypothesisContext(input.anchor(), input.gen(), input.hypothesisTitle());
        var compile = compilePhase.run(hypoCtx, input.runId());
        SupervisorVerdictDTO verdict = null;
        for (int iter = 0; iter < MAX_ITERATIONS; iter++) {
            verdict = supervisorPhase.run(
                new SupervisorPhase.Input(hypoCtx, compile, supervisorChatId, iter, MAX_ITERATIONS),
                input.runId()).dto();
            if (terminates(verdict, iter)) {
                break;
            }
            var step = applyLoopVerdict(new LoopParams(hypoCtx, compile, verdict, iter, input.runId()));
            hypoCtx = step.hypoCtx();
            compile = step.compile();
        }
        return new LoopOutcome(hypoCtx, compile, verdict);
    }

    private HypothesisResult finalizeHypothesis(HypothesisExecutionInput input, LoopOutcome outcome) {
        var pipeCtx = new PipelineContext(outcome.hypoCtx(), outcome.compile());
        var verdict = outcome.supervisorVerdict();
        boolean dead = isDead(verdict);
        boolean wantsDiagnosis = dead || needsNullPhase(outcome.compile().pipelineResult().metrics());
        var diagnosis = wantsDiagnosis ? nullPhase.run(pipeCtx, input.runId()).diagnosis().dto() : null;
        var standoff = dead ? null : standoffPhase.run(pipeCtx, input.runId());
        var compile = outcome.compile();
        return new HypothesisResult(
            input.hypothesisTitle(), outcome.hypoCtx().gen().rebuttal().rawResponse(),
            compile.compiler().dto(), compile.pipelineResult(),
            compile.scepticReview().dto(), diagnosis,
            standoff != null ? standoff.advocate().dto() : null,
            standoff != null ? standoff.prosecutor().dto() : null,
            verdict);
    }

    private static boolean terminates(SupervisorVerdictDTO verdict, int iter) {
        return verdict.decision() == SupervisorVerdictDTO.Decision.PASS_THROUGH
               || iter + 1 >= MAX_ITERATIONS;
    }

    private LoopStep applyLoopVerdict(LoopParams params) {
        return switch (params.verdict().decision()) {
            case LOOP_TO_SCEPTIC -> applyScepticLoop(params);
            case LOOP_TO_GENERATOR -> applyGeneratorLoop(params);
            case PASS_THROUGH -> throw new IllegalStateException(
                "PASS_THROUGH must be handled by terminates() before applyLoopVerdict");
        };
    }

    private static boolean isDead(SupervisorVerdictDTO verdict) {
        return verdict != null
               && verdict.decision() == SupervisorVerdictDTO.Decision.PASS_THROUGH
               && verdict.passReason() == SupervisorVerdictDTO.PassThroughReason.HYPOTHESIS_DEAD;
    }

    private LoopStep applyScepticLoop(LoopParams params) {
        var verdict = params.verdict();
        requireFocusRequest(verdict);
        var compile = compilePhase.continueSceptic(
            new CompilePhase.ContinueInput(params.hypoCtx(), params.compile(),
                verdict.focusRequest(), params.iter() + 1),
            params.runId());
        return new LoopStep(params.hypoCtx(), compile);
    }

    private LoopStep applyGeneratorLoop(LoopParams params) {
        var verdict = params.verdict();
        requireRefinementRequest(verdict);
        var refined = genPhase.runSupervisorRefinement(
            new GenPhase.RefinementInput(params.hypoCtx(),
                verdict.refinementRequest(), params.iter() + 1),
            params.runId());
        var newCtx = withRefinedRebuttal(params.hypoCtx(), refined);
        var newCompile = compilePhase.run(newCtx, params.runId());
        return new LoopStep(newCtx, newCompile);
    }

    private static void requireFocusRequest(SupervisorVerdictDTO verdict) {
        if (verdict.focusRequest() == null || verdict.focusRequest().isBlank()) {
            throw new IllegalStateException(
                "Supervisor returned LOOP_TO_SCEPTIC without focusRequest: " + verdict.explanation());
        }
    }

    private static void requireRefinementRequest(SupervisorVerdictDTO verdict) {
        if (verdict.refinementRequest() == null || verdict.refinementRequest().isBlank()) {
            throw new IllegalStateException(
                "Supervisor returned LOOP_TO_GENERATOR without refinementRequest: " + verdict.explanation());
        }
    }

    private static HypothesisContext withRefinedRebuttal(
        HypothesisContext prior, StepOutput<HypothesisGenerationDTO> refined) {
        var prevGen = prior.gen();
        var newGen = new GenPhase.Output(
            prevGen.chatId(), prevGen.generator(), prevGen.sceptic(), refined);
        return new HypothesisContext(prior.anchor(), newGen, prior.hypothesisId());
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
        int iter,
        String runId
    ) {}
}
