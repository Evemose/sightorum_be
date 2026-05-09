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
 * Routes the next step of the per-hypothesis loop. Reads one trio iteration's
 * outputs (compile, pipeline, sceptic review) plus the supervisor's running
 * notes and returns a verdict: pass through, loop back to the sceptic with a
 * focus, or loop back to the generator with a refinement.
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
        var serialized = serialize(input.compile());
        var id = supervisorId(input, serialized);
        var prompt = buildPrompt(input, serialized);
        var stepInput = new StepExecutionInput(id, prompt,
            input.hypoCtx().anchor().swarm().schema(), PhaseScope.runId());

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

    @SneakyThrows
    private Serialized serialize(CompilePhase.Output compile) {
        return new Serialized(
            mapper.writeValueAsString(compile.pipelineResult().metrics()),
            mapper.writeValueAsString(compile.compiler().dto()),
            compile.scepticReview().rawResponse());
    }

    private EventId supervisorId(Input input, Serialized s) {
        var hypoCtx = input.hypoCtx();
        return EventId.child("supervisor",
            ContentHash.of(Map.of(
                "kind", "supervisor",
                "schema", hypoCtx.anchor().swarm().schema(),
                "iteration", Integer.toString(input.iteration()),
                "hypothesisSpec", hypoCtx.gen().rebuttal().rawResponse(),
                "pipelineOutput", s.pipelineJson(),
                "compilerSpec", s.specJson(),
                "scepticReview", s.scepticRaw(),
                "priorNotes", input.priorNotes())),
            List.of(input.compile().scepticReview().id()),
            Map.of("anchor", hypoCtx.anchor().anchorTag(),
                "hypothesis", hypoCtx.hypothesisId(),
                "iteration", Integer.toString(input.iteration())));
    }

    private String buildPrompt(Input input, Serialized s) {
        var hypoCtx = input.hypoCtx();
        var notes = input.priorNotes().isBlank() ? "(none — first iteration)" : input.priorNotes();
        return config.supervisor().userPromptTemplate()
            .replace("{{HYPOTHESIS_SPEC}}", hypoCtx.gen().rebuttal().rawResponse())
            .replace("{{COMPILER_SPEC}}", s.specJson())
            .replace("{{PIPELINE_OUTPUT}}", s.pipelineJson())
            .replace("{{SCEPTIC_REVIEW}}", s.scepticRaw())
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

    private record Serialized(String pipelineJson, String specJson, String scepticRaw) {}
}
