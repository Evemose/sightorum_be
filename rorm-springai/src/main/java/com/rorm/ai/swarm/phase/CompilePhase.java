package com.rorm.ai.swarm.phase;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.DurableRuntime;
import com.rorm.JobSpec;
import com.rorm.ai.ModelSpaceResolver;
import com.rorm.ai.chat.MemoryInclude;
import com.rorm.ai.swarm.*;
import com.rorm.ai.swarm.dto.CompilerCorrectionDTO;
import com.rorm.ai.swarm.executor.StepExecutionInput;
import com.rorm.ml.MlTrainingService;
import com.rorm.ml.PipelineSpecConverter;
import com.rorm.ml.dto.PipelineSpecRequest;
import com.rorm.ml.stream.JobEvent;
import com.rorm.ml.stream.JobFailedException;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class CompilePhase {

    private static final TypeReference<StepOutput<PipelineSpecRequest>> COMPILER_REF = new TypeReference<>() {};
    private static final TypeReference<StepOutput<CompilerCorrectionDTO>> SCEPTIC_REF = new TypeReference<>() {};
    private static final EnumSet<MemoryInclude> SCEPTIC_CONTINUATION_MEMORY = EnumSet.of(
        MemoryInclude.TOOL_CALLS, MemoryInclude.TOOL_RESPONSES, MemoryInclude.THINKING);
    private static final EnumSet<MemoryInclude> COMPILER_REROUTE_MEMORY = EnumSet.of(
        MemoryInclude.TOOL_CALLS, MemoryInclude.TOOL_RESPONSES, MemoryInclude.THINKING);

    private final DurableRuntime runtime;
    private final ObjectMapper mapper;
    private final DurableSwarmConfig config;
    private final PipelineSpecConverter pipelineSpecConverter;
    private final MlTrainingService mlService;
    private final ModelSpaceResolver modelSpaceResolver;

    public Output run(HypothesisContext hypoCtx, String runId) {
        return ScopedValue.where(PhaseScope.RUN_ID, runId).call(() -> doRun(hypoCtx));
    }

    public Output continueSceptic(ContinueInput cont, String runId) {
        return ScopedValue.where(PhaseScope.RUN_ID, runId).call(() -> doContinueSceptic(cont));
    }

    private Output doContinueSceptic(ContinueInput cont) {
        var prior = cont.prior();
        var id = continueScepticId(cont);
        var stepInput = new StepExecutionInput(id, cont.supervisorFocus(),
            cont.hypoCtx().anchor().swarm().schema(), PhaseScope.runId(),
            prior.scepticChatId(), SCEPTIC_CONTINUATION_MEMORY,
            Map.of("pipelineRunId", prior.pipelineResult().jobId().toString()));
        log.info("[swarm] Compiler sceptic continuation iter={} hypothesis={}",
            cont.iteration(), cont.hypoCtx().hypothesisId());
        var sessionId = "compilerScepticExecutor-" + id.token();
        var newSceptic = mapper.convertValue(
            runtime.submit(sessionId, new JobSpec(
                "compilerScepticExecutor", "execute",
                new Object[]{stepInput},
                new String[]{StepExecutionInput.class.getName()}
            )), SCEPTIC_REF);
        return new Output(prior.compiler(), prior.pipelineResult(), newSceptic, prior.scepticChatId());
    }

    private EventId continueScepticId(ContinueInput cont) {
        var hypoCtx = cont.hypoCtx();
        return EventId.child("compiler-sceptic",
            ContentHash.of(Map.of(
                "kind", "compiler-sceptic-continuation",
                "schema", hypoCtx.anchor().swarm().schema(),
                "scepticChatId", cont.prior().scepticChatId(),
                "iteration", Integer.toString(cont.iteration()),
                "supervisorFocus", cont.supervisorFocus())),
            List.of(cont.prior().scepticReview().id()),
            Map.of("anchor", hypoCtx.anchor().anchorTag(),
                "hypothesis", hypoCtx.hypothesisId(),
                "iteration", Integer.toString(cont.iteration())));
    }

    private Output doRun(HypothesisContext hypoCtx) {
        var compilerResult = runCompiler(hypoCtx);
        log.info("[swarm] Submitting pipeline for {}", hypoCtx.hypothesisId());
        var pipelineResult = submitPipeline(hypoCtx, compilerResult.dto());
        var scepticChatId = scepticChatIdFor(compilerResult);
        var scepticResult = runSceptic(hypoCtx, compilerResult, pipelineResult, scepticChatId);
        return new Output(compilerResult, pipelineResult, scepticResult, scepticChatId);
    }

    private StepOutput<PipelineSpecRequest> runCompiler(HypothesisContext hypoCtx) {
        var anchor = hypoCtx.anchor();
        var id = compilerId(hypoCtx);
        var input = new StepExecutionInput(id, compilerPrompt(hypoCtx),
            anchor.swarm().schema(), PhaseScope.runId(),
            null, COMPILER_REROUTE_MEMORY);

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
        var modelSpace = modelSpaceResolver.resolve(swarm.schema());
        var request = pipelineSpecConverter.convert(
            spec, hypoCtx.hypothesisId() + " causal verification", modelSpace, swarm.schema());
        try {
            return mlService.submit(request).await();
        } catch (JobFailedException e) {
            log.warn("Pipeline failed for hypothesis {}: code={} message={}",
                hypoCtx.hypothesisId(), e.event().errorCode(), e.event().error());
            return e.event();
        } catch (Exception e) {
            log.error("Failed to submit pipeline for hypothesis {}, error: {}", hypoCtx.hypothesisId(), e.getMessage(), e);
            throw e;
        }
    }

    private static String scepticChatIdFor(StepOutput<PipelineSpecRequest> compilerResult) {
        return "swarm-csceptic-" + compilerResult.id().token();
    }

    @SneakyThrows
    private StepOutput<CompilerCorrectionDTO> runSceptic(HypothesisContext hypoCtx,
                                                         StepOutput<PipelineSpecRequest> compilerResult,
                                                         JobEvent pipelineResult, String scepticChatId) {
        var pipelineJson = mapper.writeValueAsString(pipelineOutputPayload(pipelineResult));
        var specJson = mapper.writeValueAsString(compilerResult.dto());
        var id = scepticId(hypoCtx, pipelineJson, specJson);
        var prompt = config.compilerSceptic().userPromptTemplate()
            .replace("{{HYPOTHESIS_SPEC}}", hypoCtx.gen().rebuttal().rawResponse())
            .replace("{{PIPELINE_SPEC}}", specJson)
            .replace("{{PIPELINE_OUTPUT}}", pipelineJson);
        var input = new StepExecutionInput(id, prompt,
            hypoCtx.anchor().swarm().schema(), PhaseScope.runId(),
            scepticChatId, null, Map.of("pipelineRunId", pipelineResult.jobId().toString()));
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
                "domainKnowledge", hypoCtx.anchor().recon().domain().rawResponse(),
                "hypothesis", hypoCtx.hypothesisId()
            )),
            List.of(hypoCtx.gen().rebuttal().id()),
            Map.of("anchor", hypoCtx.anchor().anchorTag(), "hypothesis", hypoCtx.hypothesisId()));
    }

    private String compilerPrompt(HypothesisContext hypoCtx) {
        return config.executorCompiler().userPromptTemplate()
            .replace("{{HYPOTHESIS_SPEC}}", hypoCtx.gen().rebuttal().rawResponse())
            .replace("{{DOMAIN_KNOWLEDGE}}", hypoCtx.anchor().recon().domain().rawResponse());
    }

    private static Map<String, Object> pipelineOutputPayload(JobEvent event) {
        var payload = new java.util.LinkedHashMap<String, Object>();
        payload.put("status", event.isFailed() ? "FAILED" : event.isSuccess() ? "SUCCESS" : event.eventType().name());
        if (event.isFailed()) {
            payload.put("errorCode", event.errorCode());
            payload.put("errorMessage", event.error());
        }
        payload.put("metrics", event.metrics());
        if (event.metadata() != null && !event.metadata().isEmpty()) {
            payload.put("metadata", event.metadata());
        }
        return payload;
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
        StepOutput<CompilerCorrectionDTO> scepticReview,
        String scepticChatId
    ) {}

    public record ContinueInput(
        HypothesisContext hypoCtx,
        Output prior,
        String supervisorFocus,
        int iteration
    ) {}
}
