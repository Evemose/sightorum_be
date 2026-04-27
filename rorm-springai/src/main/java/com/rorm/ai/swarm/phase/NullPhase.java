package com.rorm.ai.swarm.phase;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.DurableRuntime;
import com.rorm.JobSpec;
import com.rorm.ai.swarm.*;
import com.rorm.ai.swarm.dto.ForensicDiagnosisDTO;
import com.rorm.ai.swarm.executor.StepExecutionInput;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Forensic pathologist / null-result phase: submits the forensic-pathologist
 * step as a durable invocation to interpret the causal verification output.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NullPhase {

    private static final TypeReference<StepOutput<ForensicDiagnosisDTO>> DIAGNOSIS_REF = new TypeReference<>() {};

    private final DurableRuntime runtime;
    private final ObjectMapper mapper;
    private final DurableSwarmConfig config;

    public Output run(PipelineContext pipeCtx, String runId) {
        return ScopedValue.where(PhaseScope.RUN_ID, runId).call(() -> doRun(pipeCtx));
    }

    private Output doRun(PipelineContext pipeCtx) {
        var hypoCtx = pipeCtx.hypothesis();
        var id = forensicId(pipeCtx);
        var input = new StepExecutionInput(id, buildPrompt(pipeCtx),
            hypoCtx.anchor().swarm().schema(),
            PhaseScope.runId());

        log.info("[swarm] Forensic pathologist for {}", hypoCtx.hypothesisId());
        var sessionId = "forensicPathologistExecutor-" + id.token();
        var result = mapper.convertValue(
            runtime.submit(sessionId, new JobSpec(
                "forensicPathologistExecutor", "execute",
                new Object[]{input},
                new String[]{StepExecutionInput.class.getName()}
            )), DIAGNOSIS_REF);
        return new Output(result);
    }

    private EventId forensicId(PipelineContext pipeCtx) {
        var hypoCtx = pipeCtx.hypothesis();
        return EventId.child("forensic-pathologist",
            ContentHash.of(Map.of(
                "kind", "forensic-pathologist",
                "schema", hypoCtx.anchor().swarm().schema(),
                "hypothesisSpec", hypoCtx.gen().rebuttal().rawResponse(),
                "domainKnowledge", hypoCtx.anchor().recon().domain().rawResponse(),
                "pipelineOutput", writeJson(pipeCtx.compile().pipelineResult()))),
            List.of(pipeCtx.compile().compiler().id()),
            Map.of("anchor", hypoCtx.anchor().anchorTag(), "hypothesis", hypoCtx.hypothesisId()));
    }

    private String buildPrompt(PipelineContext pipeCtx) {
        var hypoCtx = pipeCtx.hypothesis();
        return config.forensicPathologist().userPromptTemplate()
            .replace("{{HYPOTHESIS_SPEC}}", hypoCtx.gen().rebuttal().rawResponse())
            .replace("{{DOMAIN_KNOWLEDGE}}", hypoCtx.anchor().recon().domain().rawResponse())
            .replace("{{PIPELINE_OUTPUT}}", writeJson(pipeCtx.compile().pipelineResult()));
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return value.toString();
        }
    }

    public record Output(StepOutput<ForensicDiagnosisDTO> diagnosis) {}
}
