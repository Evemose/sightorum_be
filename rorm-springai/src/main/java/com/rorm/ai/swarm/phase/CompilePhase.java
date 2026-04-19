package com.rorm.ai.swarm.phase;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.DurableRuntime;
import com.rorm.JobSpec;
import com.rorm.ai.swarm.ContentHash;
import com.rorm.ai.swarm.DurableSwarmConfig;
import com.rorm.ai.swarm.EventId;
import com.rorm.ai.swarm.PhaseScope;
import com.rorm.ai.swarm.StepOutput;
import com.rorm.ai.swarm.dto.CompilerCorrectionDTO;
import com.rorm.ai.swarm.executor.StepExecutionInput;
import com.rorm.ml.MlTrainingService;
import com.rorm.ml.PipelineSpecConverter;
import com.rorm.ml.dto.PipelineSpecRequest;
import com.rorm.ml.stream.JobEvent;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class CompilePhase {

    private static final TypeReference<StepOutput<PipelineSpecRequest>> COMPILER_REF = new TypeReference<>() {};
    private static final TypeReference<StepOutput<CompilerCorrectionDTO>> SCEPTIC_REF = new TypeReference<>() {};

    private final DurableRuntime runtime;
    private final ObjectMapper mapper;
    private final DurableSwarmConfig config;
    private final PipelineSpecConverter pipelineSpecConverter;
    private final MlTrainingService mlService;

    public Output run(HypothesisContext hypoCtx, String runId) {
        return ScopedValue.where(PhaseScope.RUN_ID, runId).call(() -> doRun(hypoCtx));
    }

    private Output doRun(HypothesisContext hypoCtx) {
        var compilerResult = runCompiler(hypoCtx);
        log.info("[swarm] Submitting pipeline for {}", hypoCtx.hypothesisId());
        var pipelineResult = submitPipeline(hypoCtx, compilerResult.dto());

        var scepticResult = runSceptic(hypoCtx, compilerResult, pipelineResult);

        return new Output(compilerResult, pipelineResult, scepticResult);
    }

    private StepOutput<PipelineSpecRequest> runCompiler(HypothesisContext hypoCtx) {
        var anchor = hypoCtx.anchor();
        var id = compilerId(hypoCtx);
        var input = new StepExecutionInput(id, compilerPrompt(hypoCtx),
            anchor.swarm().schema(), anchor.swarm().modelSpace(), PhaseScope.runId());

        log.info("[swarm] Compiling hypothesis {}", hypoCtx.hypothesisId());
        var sessionId = "compilerExecutor-" + id.token();
        return mapper.convertValue(
            runtime.submit(sessionId, new JobSpec(
                "compilerExecutor", "execute",
                new Object[]{input},
                new String[]{StepExecutionInput.class.getName()}
            )), COMPILER_REF);
    }

    private JobEvent submitPipeline(HypothesisContext hypoCtx, PipelineSpecRequest spec) {
        var swarm = hypoCtx.anchor().swarm();
        var request = pipelineSpecConverter.convert(
            spec, hypoCtx.hypothesisId() + " causal verification", swarm.modelSpace(), swarm.schema());
        return mlService.submit(request).await();
    }

    @SneakyThrows
    private StepOutput<CompilerCorrectionDTO> runSceptic(HypothesisContext hypoCtx,
                                                         StepOutput<PipelineSpecRequest> compilerResult,
                                                         JobEvent pipelineResult) {
        var anchor = hypoCtx.anchor();
        var pipelineJson = mapper.writeValueAsString(pipelineResult.metrics());
        var specJson = mapper.writeValueAsString(compilerResult.dto());

        var id = scepticId(hypoCtx, pipelineJson, specJson);
        var prompt = config.compilerSceptic().userPromptTemplate()
            .replace("{{HYPOTHESIS_SPEC}}", hypoCtx.gen().rebuttal().rawResponse())
            .replace("{{PIPELINE_SPEC}}", specJson)
            .replace("{{PIPELINE_OUTPUT}}", pipelineJson);
        var input = new StepExecutionInput(id, prompt,
            anchor.swarm().schema(), anchor.swarm().modelSpace(), PhaseScope.runId(),
            null, null, Map.of("pipelineRunId", pipelineResult.jobId().toString()));

        log.info("[swarm] Compiler sceptic reviewing {}", hypoCtx.hypothesisId());
        var sessionId = "compilerScepticExecutor-" + id.token();
        return mapper.convertValue(
            runtime.submit(sessionId, new JobSpec(
                "compilerScepticExecutor", "execute",
                new Object[]{input},
                new String[]{StepExecutionInput.class.getName()}
            )), SCEPTIC_REF);
    }

    private EventId compilerId(HypothesisContext hypoCtx) {
        return EventId.child("compiler",
            ContentHash.of(Map.of(
                "kind", "compiler",
                "schema", hypoCtx.anchor().swarm().schema(),
                "hypothesisSpec", hypoCtx.gen().rebuttal().rawResponse(),
                "domainKnowledge", hypoCtx.anchor().recon().domain().rawResponse())),
            List.of(hypoCtx.gen().rebuttal().id()),
            Map.of("anchor", hypoCtx.anchor().anchorTag(), "hypothesis", hypoCtx.hypothesisId()));
    }

    private String compilerPrompt(HypothesisContext hypoCtx) {
        return config.executorCompiler().userPromptTemplate()
            .replace("{{HYPOTHESIS_SPEC}}", hypoCtx.gen().rebuttal().rawResponse())
            .replace("{{DOMAIN_KNOWLEDGE}}", hypoCtx.anchor().recon().domain().rawResponse());
    }

    private EventId scepticId(HypothesisContext hypoCtx, String pipelineOutput, String pipelineSpec) {
        return EventId.child("compiler-sceptic",
            ContentHash.of(Map.of(
                "kind", "compiler-sceptic",
                "schema", hypoCtx.anchor().swarm().schema(),
                "pipelineSpec", pipelineSpec,
                "pipelineOutput", pipelineOutput)),
            List.of(compilerId(hypoCtx)),
            Map.of("anchor", hypoCtx.anchor().anchorTag(), "hypothesis", hypoCtx.hypothesisId()));
    }

    public record Output(
        StepOutput<PipelineSpecRequest> compiler,
        JobEvent pipelineResult,
        StepOutput<CompilerCorrectionDTO> scepticReview
    ) {}
}
