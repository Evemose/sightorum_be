package com.rorm.ai.swarm.phase;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.DurableRuntime;
import com.rorm.JobSpec;
import com.rorm.ai.chat.MemoryInclude;
import com.rorm.ai.swarm.*;
import com.rorm.ai.swarm.dto.CompilerCorrectionDTO;
import com.rorm.ai.swarm.dto.CompilerResultDTO;
import com.rorm.ai.swarm.executor.StepExecutionInput;
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

    private static final TypeReference<StepOutput<CompilerResultDTO>> COMPILER_REF = new TypeReference<>() {};
    private static final TypeReference<StepOutput<CompilerCorrectionDTO>> SCEPTIC_REF = new TypeReference<>() {};
    private static final EnumSet<MemoryInclude> COMPILER_REROUTE_MEMORY = EnumSet.of(
        MemoryInclude.TOOL_CALLS, MemoryInclude.TOOL_RESPONSES, MemoryInclude.THINKING);

    private final DurableRuntime runtime;
    private final ObjectMapper mapper;
    private final DurableSwarmConfig config;

    public Output run(HypothesisContext hypoCtx, String runId) {
        return ScopedValue.where(PhaseScope.RUN_ID, runId).call(() -> doRun(hypoCtx));
    }

    public Output continueCompiler(ContinueInput cont, String runId) {
        return ScopedValue.where(PhaseScope.RUN_ID, runId).call(() -> doContinueCompiler(cont));
    }

    private Output doContinueCompiler(ContinueInput cont) {
        var hypoCtx = cont.hypoCtx();
        var prior = cont.prior();
        var id = continueCompilerId(cont);
        var stepInput = StepExecutionInput.builder()
            .eventId(id).userPrompt(cont.supervisorFocus())
            .schema(hypoCtx.anchor().swarm().schema()).runId(PhaseScope.runId())
            .chatId(compilerChatIdFor(prior.compiler())).memoryIncludes(COMPILER_REROUTE_MEMORY)
            .build();
        log.info("[swarm] Compiler continuation iter={} hypothesis={}",
            cont.iteration(), hypoCtx.hypothesisId());
        var sessionId = "compilerExecutor-" + id.token();
        var newCompiler = mapper.convertValue(
            runtime.submit(sessionId, new JobSpec(
                "compilerExecutor", "execute",
                new Object[]{stepInput},
                new String[]{StepExecutionInput.class.getName()}
            )), COMPILER_REF);
        var combined = new StepOutput<>(newCompiler.id(), newCompiler.dto(),
            IterationHistoryRenderer.append(
                prior.compiler().rawResponse(), newCompiler.rawResponse(),
                "compiler continuation", cont.iteration()));
        var scepticChatId = scepticChatIdFor(combined);
        var scepticResult = runSceptic(hypoCtx, combined, scepticChatId);
        return new Output(combined, scepticResult, scepticChatId);
    }

    private EventId continueCompilerId(ContinueInput cont) {
        var hypoCtx = cont.hypoCtx();
        return EventId.child("compiler",
            ContentHash.of(Map.of(
                "kind", "compiler-continuation",
                "schema", hypoCtx.anchor().swarm().schema(),
                "compilerChatId", compilerChatIdFor(cont.prior().compiler()),
                "iteration", Integer.toString(cont.iteration()),
                "supervisorFocus", cont.supervisorFocus())),
            List.of(cont.prior().compiler().id(), cont.supervisorId()),
            Map.of("anchor", hypoCtx.anchor().anchorTag(),
                "hypothesis", hypoCtx.hypothesisId(),
                "iteration", Integer.toString(cont.iteration())));
    }

    private static String compilerChatIdFor(StepOutput<CompilerResultDTO> compilerResult) {
        return "swarm-compiler-" + compilerResult.id().token();
    }

    private static String scepticChatIdFor(StepOutput<CompilerResultDTO> compilerResult) {
        return "swarm-csceptic-" + compilerResult.id().token();
    }

    @SneakyThrows
    private StepOutput<CompilerCorrectionDTO> runSceptic(HypothesisContext hypoCtx,
                                                         StepOutput<CompilerResultDTO> compilerResult,
                                                         String scepticChatId) {
        var compilerOutput = compilerResult.rawResponse();
        var id = scepticId(hypoCtx, compilerResult);
        var dto = compilerResult.dto();
        var primaryRunId = dto.selectedRunIds().isEmpty() ? null : dto.selectedRunIds().getFirst();
        var prompt = config.compilerSceptic().userPromptTemplate()
            .replace("{{HYPOTHESIS_SPEC}}", hypoCtx.gen().rebuttal().rawResponse())
            .replace("{{COMPILER_OUTPUT}}", compilerOutput);
        var pipelineRunIdContext = primaryRunId != null
            ? Map.<String, Object>of("pipelineRunId", primaryRunId)
            : Map.<String, Object>of();
        var input = StepExecutionInput.builder()
            .eventId(id).userPrompt(prompt).schema(hypoCtx.anchor().swarm().schema())
            .runId(PhaseScope.runId()).chatId(scepticChatId)
            .toolContextEntries(pipelineRunIdContext)
            .build();
        log.info("[swarm] Compiler sceptic guardrail review for {}", hypoCtx.hypothesisId());
        var sessionId = "compilerScepticExecutor-" + id.token();
        return mapper.convertValue(
            runtime.submit(sessionId, new JobSpec(
                "compilerScepticExecutor", "execute",
                new Object[]{input},
                new String[]{StepExecutionInput.class.getName()}
            )), SCEPTIC_REF);
    }

    private EventId scepticId(HypothesisContext hypoCtx, StepOutput<CompilerResultDTO> compiler) {
        return EventId.child("compiler-sceptic",
            ContentHash.of(Map.of(
                "kind", "compiler-sceptic",
                "schema", hypoCtx.anchor().swarm().schema(),
                "compilerOutput", compiler.rawResponse())),
            List.of(compiler.id()),
            Map.of("anchor", hypoCtx.anchor().anchorTag(), "hypothesis", hypoCtx.hypothesisId()));
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

    private Output doRun(HypothesisContext hypoCtx) {
        var compilerResult = runCompiler(hypoCtx);
        var scepticChatId = scepticChatIdFor(compilerResult);
        var scepticResult = runSceptic(hypoCtx, compilerResult, scepticChatId);
        return new Output(compilerResult, scepticResult, scepticChatId);
    }

    private String compilerPrompt(HypothesisContext hypoCtx) {
        return config.executorCompiler().userPromptTemplate()
            .replace("{{HYPOTHESIS_SPEC}}", hypoCtx.gen().rebuttal().rawResponse())
            .replace("{{DOMAIN_KNOWLEDGE}}", hypoCtx.anchor().recon().domain().rawResponse());
    }

    private StepOutput<CompilerResultDTO> runCompiler(HypothesisContext hypoCtx) {
        var anchor = hypoCtx.anchor();
        var id = compilerId(hypoCtx);
        var input = StepExecutionInput.builder()
            .eventId(id).userPrompt(compilerPrompt(hypoCtx)).schema(anchor.swarm().schema())
            .runId(PhaseScope.runId()).memoryIncludes(COMPILER_REROUTE_MEMORY)
            .build();

        log.info("[swarm] Compiling hypothesis {}", hypoCtx.hypothesisId());
        var sessionId = "compilerExecutor-" + id.token();
        return mapper.convertValue(
            runtime.submit(sessionId, new JobSpec(
                "compilerExecutor", "execute",
                new Object[]{input},
                new String[]{StepExecutionInput.class.getName()}
            )), COMPILER_REF);
    }

    /**
     * Compile-phase outputs for a hypothesis. The compiler iterates internally
     * by calling {@code executePipeline}; its {@link StepOutput#toolCalls()}
     * holds every run it submitted. The sceptic acts as a guardrail review on
     * those runs and may itself invoke {@code executePipeline} or
     * {@code reexecuteCausalPipeline} for sanity-checks; the sceptic's
     * {@link StepOutput#toolCalls()} carries those (accumulated atop the
     * compiler's via {@link com.rorm.ai.swarm.communication.ToolCallRegistry}).
     */
    public record Output(
        StepOutput<CompilerResultDTO> compiler,
        StepOutput<CompilerCorrectionDTO> scepticReview,
        String scepticChatId
    ) {}

    public record ContinueInput(
        HypothesisContext hypoCtx,
        Output prior,
        String supervisorFocus,
        int iteration,
        EventId supervisorId
    ) {}
}
