package com.rorm.ai.swarm.phase;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.DurableRuntime;
import com.rorm.JobSpec;
import com.rorm.ai.swarm.*;
import com.rorm.ai.swarm.dto.SupervisorVerdictDTO;
import com.rorm.ai.swarm.executor.StepExecutionInput;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Routes the next step of the per-hypothesis loop. Reads one trio
 * iteration's outputs (the compiler's CompilerResultDTO with per-run
 * briefs, the sceptic's CompilerCorrectionDTO with verifications and
 * its own per-run briefs, plus the supervisor's running notes) and
 * returns a verdict: pass through, loop back to the compiler with a
 * focus, or loop back to the generator with a refinement.
 * <p>
 * Run-level data (specs and JobEvent results) is NOT dumped in the
 * supervisor's prompt — the supervisor reads briefs and drills into
 * specific run fields via queryRunSpec / queryRunResult tools when
 * needed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SupervisorPhase {

    private static final TypeReference<StepOutput<SupervisorVerdictDTO>> REF = new TypeReference<>() {};

    private final DurableRuntime runtime;
    private final ObjectMapper mapper;
    private final DurableSwarmConfig config;

    public StepOutput<SupervisorVerdictDTO> run(Input input, String runId) {
        return ScopedValue.where(PhaseScope.RUN_ID, runId).call(() -> doRun(input));
    }

    @SneakyThrows
    private StepOutput<SupervisorVerdictDTO> doRun(Input input) {
        var compile = input.compile();
        var compilerOutput = compile.compiler().rawResponse();
        var scepticRaw = compile.scepticReview().rawResponse();
        var id = supervisorId(input, compilerOutput, scepticRaw);
        var prompt = buildPrompt(input, compilerOutput, scepticRaw);
        var stepInput = StepExecutionInput.builder()
            .eventId(id).userPrompt(prompt).schema(input.hypoCtx().anchor().swarm().schema())
            .runId(PhaseScope.runId())
            .build();

        log.info("[swarm] Supervisor iter={} hypothesis={}",
            input.iteration(), input.hypoCtx().hypothesisId());
        var sessionId = "supervisorExecutor-" + id.token();
        return mapper.convertValue(
            runtime.submit(sessionId, new JobSpec(
                "supervisorExecutor", "execute",
                new Object[]{stepInput},
                new String[]{StepExecutionInput.class.getName()}
            )), REF);
    }

    private EventId supervisorId(Input input, String compilerOutput, String scepticRaw) {
        var hypoCtx = input.hypoCtx();
        return EventId.child("supervisor",
            ContentHash.of(Map.of(
                "kind", "supervisor",
                "schema", hypoCtx.anchor().swarm().schema(),
                "iteration", Integer.toString(input.iteration()),
                "hypothesisSpec", hypoCtx.gen().rebuttal().rawResponse(),
                "compilerOutput", compilerOutput,
                "scepticReview", scepticRaw,
                "priorNotes", input.priorNotes())),
            List.of(input.compile().scepticReview().id()),
            Map.of("anchor", hypoCtx.anchor().anchorTag(),
                "hypothesis", hypoCtx.hypothesisId(),
                "iteration", Integer.toString(input.iteration())));
    }

    private String buildPrompt(Input input, String compilerOutput, String scepticRaw) {
        var hypoCtx = input.hypoCtx();
        var notes = input.priorNotes().isBlank() ? "(none — first iteration)" : input.priorNotes();
        return config.supervisor().userPromptTemplate()
            .replace("{{HYPOTHESIS_ID}}", hypoCtx.hypothesisId())
            .replace("{{HYPOTHESIS_SPEC}}", hypoCtx.gen().rebuttal().rawResponse())
            .replace("{{COMPILER_OUTPUT}}", compilerOutput)
            .replace("{{SCEPTIC_REVIEW}}", scepticRaw)
            .replace("{{PRIOR_NOTES}}", notes)
            .replace("{{ITERATION}}", Integer.toString(input.iteration()))
            .replace("{{MAX_ITERATIONS}}", Integer.toString(input.maxIterations()));
    }

    public record Input(
        HypothesisContext hypoCtx,
        CompilePhase.Output compile,
        String priorNotes,
        int iteration,
        int maxIterations
    ) {}
}
