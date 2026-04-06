package com.rorm.ai.swarm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.StepJournal;
import com.rorm.ai.chat.AiChatService;
import com.rorm.ai.chat.ChatRequest;
import com.rorm.ai.prompt.PromptPlaceholders;
import com.rorm.ai.swarm.SwarmResult.AnchorResult;
import com.rorm.ai.swarm.SwarmResult.HypothesisResult;
import com.rorm.ai.swarm.agents.DurableSwarmStep;
import com.rorm.ai.swarm.agents.DurableSwarmStep.StepOutput;
import com.rorm.ai.swarm.agents.FirstLevelSwarmAgent;
import com.rorm.ai.swarm.agents.SecondarySwarmAgent;
import com.rorm.ai.swarm.dto.*;
import com.rorm.ai.swarm.dto.HypothesisGenerationDTO.Hypothesis;
import com.rorm.ml.MlTrainingService;
import com.rorm.ml.PipelineSpecConverter;
import com.rorm.ml.dto.PipelineSpecRequest;
import com.rorm.ml.stream.JobEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Durable swarm orchestrator — fully persistent between Restate invocations.
 * <p>
 * Every LLM call streams via {@link FirstLevelSwarmAgent} and is structurized
 * into a typed DTO via {@link SecondarySwarmAgent}, following the
 * {@code SwarmAgent.streamAndStructurize} pattern.
 * Events are emitted to a {@link Sinks.Many} for interactivity.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DurableSwarm {

    private final AiChatService chatService;
    private final DurableSwarmConfig config;
    private final PipelineSpecConverter pipelineSpecConverter;
    private final MlTrainingService mlService;
    private final ObjectMapper objectMapper;
    private final PromptPlaceholders promptPlaceholders;

    public SwarmResult run(SwarmInput input) {
        return run(input, Sinks.many().replay().all());
    }

    public SwarmResult run(SwarmInput input, Sinks.Many<SwarmEvent> eventSink) {
        var ctx = new PipelineContext(input, eventSink);

        // Phase 1 & 2: Scout + Domain Researcher (parallel)
        log.info("[swarm] Starting scout + domain researcher");
        var reconResults = ctx.journal.fanout("swarm:recon", StepOutput.class, List.of(
            () -> ctx.scout.execute(ctx.renderPrompt(config.scout()), eventSink),
            () -> ctx.domain.execute(ctx.renderPrompt(config.domainResearcher()), eventSink)
        ));
        @SuppressWarnings("unchecked") var scoutResult = (StepOutput<ScoutAnalysisDTO>) reconResults.get(0);
        @SuppressWarnings("unchecked") var domainResult = (StepOutput<DomainResearchDTO>) reconResults.get(1);

        // Phase 3-8: Per-anchor pipeline (fanout)
        log.info("[swarm] Starting {} anchor pipelines", input.anchors().size());
        var anchorResults = ctx.journal.fanout("swarm:anchor", AnchorResult.class,
            input.anchors().stream()
                .<Supplier<AnchorResult>>map(anchor -> () ->
                    runAnchorPipeline(ctx, anchor, scoutResult.rawResponse(), domainResult.rawResponse()))
                .toList()
        );

        return new SwarmResult(scoutResult.dto(), domainResult.dto(), anchorResults);
    }

    private AnchorResult runAnchorPipeline(PipelineContext ctx, String anchor, String scoutRaw, String domainRaw) {
        var generatorChatId = "swarm-gen-" + ctx.journal.randomUUID();

        // Phase 3: Generator
        log.info("[swarm] Generator for anchor: {}", anchor.lines().findFirst().orElse(anchor));
        var generatorPrompt = config.generator().userPromptTemplate()
            .replace("{{USER_QUERY}}", ctx.input.userQuery())
            .replace("{{ANCHOR_ENTITY}}", anchor)
            .replace("{{CLUSTER_CONTEXT}}", scoutRaw)
            .replace("{{DOMAIN_RESEARCH}}", domainRaw);
        var generatorResult = ctx.generator.execute(generatorPrompt,
            withChatId(generatorChatId), ctx.events);

        // Phase 4: Mechanical Sceptic
        log.info("[swarm] Mechanical sceptic");
        var scepticPrompt = config.mechanicalSceptic().userPromptTemplate()
            .replace("{{GENERATOR_OUTPUT}}", generatorResult.rawResponse());
        var scepticResult = ctx.sceptic.execute(scepticPrompt, ctx.events);

        // Phase 5: Generator Rebuttal (continues generator conversation)
        log.info("[swarm] Generator rebuttal");
        var rebuttalPrompt = """
            The following VERIFICATION findings challenge your output.
            For each, respond with exactly one of:
            ACCEPT — retract or narrow the claim. Provide revised block text.
            REBUT — provide a specific counter-number from your prior computations.
            NARROW — state the new scope boundary explicitly.
            Produce your COMPLETE revised structured output immediately after addressing each finding.
            
            Findings:
            %s""".formatted(scepticResult.rawResponse());
        var rebuttalResult = ctx.rebuttal.execute(rebuttalPrompt,
            withChatId(generatorChatId), ctx.events);

        // Phase 6-8: Per-hypothesis pipeline (fanout)
        var hypotheses = rebuttalResult.dto().hypotheses();
        log.info("[swarm] {} hypotheses, running compiler + pipeline", hypotheses.size());
        var hypothesisResults = ctx.journal.fanout("swarm:hypothesis", HypothesisResult.class,
            hypotheses.stream()
                .<Supplier<HypothesisResult>>map(h -> () ->
                    runHypothesisPipeline(ctx, h, domainRaw))
                .toList()
        );

        return new AnchorResult(anchor, generatorChatId, generatorResult.dto(),
            scepticResult.dto(), rebuttalResult.dto(), hypothesisResults);
    }

    private static UnaryOperator<ChatRequest.Builder> withChatId(String chatId) {
        return b -> b.withChatId(chatId);
    }

    // ── Per-run context ──────────────────────────────────────────────────

    private HypothesisResult runHypothesisPipeline(PipelineContext ctx, Hypothesis hypothesis, String domainRaw) {
        // Phase 6: Executor Compiler
        log.info("[swarm] Compiling hypothesis {}", hypothesis.id());
        var compilerPrompt = config.executorCompiler().userPromptTemplate()
            .replace("{{HYPOTHESIS_SPEC}}", hypothesis.specification())
            .replace("{{DOMAIN_KNOWLEDGE}}", domainRaw);
        var compilerResult = ctx.compiler.execute(compilerPrompt, ctx.events);

        // Phase 7: Pipeline Execution
        log.info("[swarm] Submitting pipeline for {}", hypothesis.id());
        var pipelineResult = submitPipeline(ctx.input, compilerResult.dto(), hypothesis.id());

        // Phase 8: Forensic Pathologist
        log.info("[swarm] Forensic pathologist for {}", hypothesis.id());
        var fpPrompt = config.forensicPathologist().userPromptTemplate()
            .replace("{{HYPOTHESIS_SPEC}}", hypothesis.specification())
            .replace("{{DOMAIN_KNOWLEDGE}}", domainRaw)
            .replace("{{PIPELINE_OUTPUT}}", writeJson(pipelineResult));
        var fpResult = ctx.fp.execute(fpPrompt, ctx.events);

        return new HypothesisResult(hypothesis.id(), hypothesis.specification(),
            compilerResult.dto(), pipelineResult, fpResult.dto());
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private JobEvent submitPipeline(SwarmInput input, PipelineCompilationDTO compiled, String hypothesisId) {
        var spec = PipelineSpecRequest.builder()
            .hypothesisId(compiled.hypothesisId() != null ? compiled.hypothesisId() : hypothesisId)
            .treatment(compiled.treatment())
            .outcome(compiled.outcome())
            .treatmentForm(compiled.treatmentForm())
            .dagEdges(compiled.dagEdges())
            .dsepThreshold(compiled.dsepThreshold() != null ? compiled.dsepThreshold() : 0.03)
            .adjustmentSet(compiled.adjustmentSet() != null ? compiled.adjustmentSet() : List.of())
            .estimationVariants(compiled.estimationVariants() != null ? compiled.estimationVariants() : List.of())
            .gates(compiled.gates() != null ? compiled.gates() : Map.of())
            .sensitivity(compiled.sensitivity() != null ? compiled.sensitivity() : Map.of())
            .residualChecks(compiled.residualChecks() != null ? compiled.residualChecks() : Map.of())
            .rangeChecks(compiled.rangeChecks() != null ? compiled.rangeChecks() : Map.of())
            .build();
        var request = pipelineSpecConverter.convert(
            spec, hypothesisId + " causal verification", input.modelSpace(), input.schema());
        return mlService.submit(request).await();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return value.toString();
        }
    }

    private class PipelineContext {
        final SwarmInput input;
        final Sinks.Many<SwarmEvent> events;
        final StepJournal journal;
        final DurableSwarmStep<ScoutAnalysisDTO> scout;
        final DurableSwarmStep<DomainResearchDTO> domain;
        final DurableSwarmStep<HypothesisGenerationDTO> generator;
        final DurableSwarmStep<ScepticReviewDTO> sceptic;
        final DurableSwarmStep<HypothesisGenerationDTO> rebuttal;
        final DurableSwarmStep<PipelineCompilationDTO> compiler;
        final DurableSwarmStep<ForensicDiagnosisDTO> fp;

        PipelineContext(SwarmInput input, Sinks.Many<SwarmEvent> events) {
            this.input = input;
            this.events = events;
            this.journal = StepJournal.current();

            var summarizer = new SecondarySwarmAgent(
                config.summarizer(), chatService,
                input.schema(), input.modelSpace(), promptPlaceholders);

            this.scout = step(config.scout(), summarizer, "scout",
                ScoutAnalysisDTO.class, SwarmEvent.DurableScoutStarted::new, SwarmEvent.DurableScoutFinished::new);
            this.domain = step(config.domainResearcher(), summarizer, "domain",
                DomainResearchDTO.class, SwarmEvent.DomainResearcherStarted::new, SwarmEvent.DomainResearcherFinished::new);
            this.generator = step(config.generator(), summarizer, "generator",
                HypothesisGenerationDTO.class, SwarmEvent.GeneratorStarted::new, SwarmEvent.GeneratorFinished::new);
            this.sceptic = step(config.mechanicalSceptic(), summarizer, "sceptic",
                ScepticReviewDTO.class, SwarmEvent.ScepticStarted::new, SwarmEvent.ScepticFinished::new);
            this.rebuttal = step(config.generator(), summarizer, "rebuttal",
                HypothesisGenerationDTO.class, SwarmEvent.RebuttalStarted::new, SwarmEvent.RebuttalFinished::new);
            this.compiler = step(config.executorCompiler(), summarizer, "compiler",
                PipelineCompilationDTO.class, SwarmEvent.CompilerStarted::new, SwarmEvent.CompilerFinished::new);
            this.fp = step(config.forensicPathologist(), summarizer, "fp",
                ForensicDiagnosisDTO.class, SwarmEvent.ForensicPathologistStarted::new, SwarmEvent.ForensicPathologistFinished::new);
        }

        private <T> DurableSwarmStep<T> step(
            AgentModelConfig agentConfig, SecondarySwarmAgent summarizer, String prefix,
            Class<T> responseType,
            java.util.function.BiFunction<String, reactor.core.publisher.Flux<String>,
                SwarmEvent.StartEvent> startFactory,
            DurableSwarmStep.EndEventFactory<T> endFactory
        ) {
            var agent = new FirstLevelSwarmAgent(agentConfig, chatService,
                input.schema(), input.modelSpace(), promptPlaceholders);
            return new DurableSwarmStep<>(agent, summarizer, responseType, prefix, startFactory, endFactory);
        }

        String renderPrompt(AgentModelConfig agentConfig) {
            return agentConfig.userPromptTemplate().replace("{{USER_QUERY}}", input.userQuery());
        }
    }
}
