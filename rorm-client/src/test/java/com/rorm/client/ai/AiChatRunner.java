package com.rorm.client.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.DurableRuntime;
import com.rorm.JobSpec;
import com.rorm.StepJournal;
import com.rorm.ai.chat.*;
import com.rorm.ai.prompt.PromptPlaceholders;
import com.rorm.ai.swarm.*;
import com.rorm.ai.swarm.agents.SecondarySwarmAgent;
import com.rorm.ai.swarm.phase.*;
import com.rorm.client.ai.AiChatRunner.AgentJP;
import com.rorm.client.metamodel.MetamodelService;
import com.rorm.ml.MlTrainingService;
import com.rorm.ml.PipelineSpecConverter;
import com.rorm.ml.stream.JobEvent;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import reactor.core.publisher.Sinks;

import java.util.List;

import static com.rorm.client.ai.SampleRunLog.*;
import static com.rorm.client.ai.SwarmPrompts.*;

/**
 * Manual runner for testing AI chat workflows against real data.
 * Requires compose stack running (postgres + redis + ml-service).
 * Run individual tests from IDE — not meant for CI.
 */
@SpringBootTest
@Import(AgentJP.class)
@ActiveProfiles("dev")
@SuppressWarnings("NewClassNamingConvention")
class AiChatRunner {

    /**
     * Change this to match a schema you have imported.
     */
    static final String SCHEMA = "cold_chain";

    @Autowired
    private DurableRuntime durableRuntime;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void simpleChat() throws Exception {
        //noinspection ConstantValue
        if (true) { // guard from accidental execution
            var submit = durableRuntime.submit("runner-genphase-1", new JobSpec(
                "agentJp",
                "runGenPhase"
            ));
//            durableRuntime.submit("runner-compile-from-genphase-1", new JobSpec(
//                "agentJp",
//                "runCompilePipelineFromGen",
//                new Object[]{submit},
//                new String[]{GenPhase.Output.class.getName()}
//            ));
        }
    }

    @Test
    void desc() throws Exception {
        //noinspection ConstantValue
        if (true) { // guard from accidental execution
            durableRuntime.submit("runner-desc-1", new JobSpec(
                "agentJp",
                "runDescriptiveSmoke"
            ));
        }
    }

    @TestComponent("agentJp")
    public static class AgentJP {

        @Autowired
        MetamodelService metamodelService;
        @Autowired
        AiChatService chatService;
        @Autowired
        private ChatConversationFormatter chatConversationFormatter;
        @Autowired
        private ChatMemoryRepository chatMemoryRepository;
        @Autowired
        private ObjectMapper objectMapper;
        @Autowired
        private PipelineSpecConverter pipelineSpecConverter;
        @Autowired
        private MlTrainingService mlService;
        @Autowired
        private DurableSwarmConfig config;
        @Autowired
        private PromptPlaceholders promptPlaceholders;

        public void runGenerator() {
            var modelSpace = metamodelService.getModelSpace(SCHEMA);
            var genUser = SwarmPrompts.GENERATOR_USER.replace(
                "{{USER_QUERY}}",
                "How can I decrease excursion rates"
            ).replace(
                "{{DOMAIN_RESEARCH}}",
                SAMPLE_DOMAIN_RESEARCH
            ).replace(
                "{{ANCHOR_ENTITY}}",
                SAMPLE_ANCHOR
            ).replace(
                "{{CLUSTER_CONTEXT}}",
                SAMPLE_SURVEY
            );
            chatService.stream(
                    ChatRequest.usingData(SCHEMA, modelSpace)
                        .withToolGroups(ToolGroup.QUERY, ToolGroup.DATA_RELATIONS)
                        .withThinkingLevel(ThinkingLevel.HIGH)
                        .withSystemPrompt(SwarmPrompts.GENERATOR_SYSTEM)
                        .withModelName("claude-opus-4-6")
                        .withCachingStrategyFunction(GENERATOR_CACHE_STRATEGY)
                        .ask(genUser)
                )
                .doOnError(e -> System.err.println("Error during chat: " + e.getMessage()))
                .doOnNext(t -> {
                    System.out.print(t);
                    System.out.flush();
                })
                .blockLast();
        }

        public void runDescriptiveSmoke() {
            var modelSpace = metamodelService.getModelSpace(SCHEMA);
            var systemPrompt = """
                You answer descriptive questions over cold-chain shipment data using a small
                toolbelt of deterministic descriptive analytics tools:
                
                - summaryStatistic — a single scalar (total/mean/median/count/ratio) over a
                  population, with checks for heterogeneity (C1), distributional shape (C2),
                  outlier sensitivity (C3), small-N (C4), denominator stability (C5),
                  and survivorship (C6).
                - rankedList — top/bottom K by a measure, with within-partition stability (R1),
                  gap-to-spread (R2), small-N per item (R4), and survivorship (R5).
                - trendSeries — a measure over time buckets with seasonality strength (T1),
                  cyclic window (T2), window sensitivity (T3), structural break via PELT (T4),
                  compositional shift in time (T5), small-N tail (T6), and multiplicative
                  variance (T7).
                - compareSides — diff/ratio/direction between two sides with frame mismatch (K1),
                  population drift (K2), Simpson reversal (K3), magnitude sanity (K4),
                  small-N (K5), and survivorship (K6).
                
                Each tool returns a JSON digest with a headline, fired checks (ordered by
                severity, top 3), and a receipts block (window, population, axes, archetype).
                Your job: pick the tool that fits the question, call it with reasonable
                arguments derived from the schema, and relay the digest in plain English while
                being explicit about any fired checks. Do not invent measures that do not
                resolve against the schema; use the provided schema context to pick real
                columns.""";

            var userPrompt = """
                Give me the mean of numeric property per shipment over the full history,
                broken down by carrier and route where relevant. Use the summaryStatistic tool
                with candidate axes for carrier and route so heterogeneity is checked, and
                tell me if any of the deterministic checks fire. If you also spot a reasonable
                ranking or trend angle from the result, make one follow-up call to rankedList
                or trendSeries and summarize that too.""";

            chatService.stream(
                    ChatRequest.usingData(SCHEMA, modelSpace)
                        .withToolGroups(ToolGroup.QUERY)
                        .withThinkingLevel(ThinkingLevel.HIGH)
                        .withSystemPrompt(systemPrompt)
                        .withModelName("claude-opus-4-6")
                        .ask(userPrompt)
                )
                .doOnError(e -> System.err.println("Error during chat: " + e.getMessage()))
                .doOnNext(t -> {
                    System.out.print(t);
                    System.out.flush();
                })
                .blockLast();
        }

        public void runScout() {
            var modelSpace = metamodelService.getModelSpace(SCHEMA);
            var scoutUser = SwarmPrompts.SURVEY_SCOUT_USER.replace(
                "{{USER_QUERY}}",
                "How can I decrease excursion rates"
            );
            chatService.stream(
                    ChatRequest.usingData(SCHEMA, modelSpace)
                        .withToolGroups(ToolGroup.QUERY)
                        .withThinkingLevel(ThinkingLevel.HIGH)
                        .withSystemPrompt(SwarmPrompts.SURVEY_SCOUT_SYSTEM)
                        .withModelName("claude-sonnet-4-6")
                        .withCachingStrategyFunction(SCOUT_CACHE_STRATEGY)
                        .ask(scoutUser)
                )
                .doOnError(e -> System.err.println("Error during chat: " + e.getMessage()))
                .doOnNext(t -> {
                    System.out.print(t);
                    System.out.flush();
                })
                .blockLast();
        }

        public void runGeneratorMechanicalSceptic() {
            var modelSpace = metamodelService.getModelSpace(SCHEMA);
            var conv = chatMemoryRepository.findByConversationId("conv-dump-equipment-aging");
            var formattedGeneratorConv = chatConversationFormatter.toMarkdown(
                List.of(conv.getLast())
            );
            chatService.stream(
                    ChatRequest.usingData(SCHEMA, modelSpace)
                        .withToolGroups(ToolGroup.QUERY, ToolGroup.VERIFICATION)
                        .withThinkingLevel(ThinkingLevel.HIGH)
                        .withSystemPrompt(GENERATOR_MECHANICAL_SCEPTIC_SYSTEM)
                        .withModelName("claude-opus-4-6")
                        .withCachingStrategyFunction(GENERATOR_MECHANICAL_SCEPTIC_CACHE_STRATEGY)
                        .ask(SwarmPrompts.GENERATOR_MECHANICAL_SCEPTIC_USER.replace(
                            "{{GENERATOR_OUTPUT}}",
                            formattedGeneratorConv
                        ))
                )
                .doOnError(e -> System.err.println("Error during chat: " + e.getMessage()))
                .doOnNext(t -> {
                    System.out.print(t);
                    System.out.flush();
                })
                .blockLast();
        }

        public void runGeneratorScepticReportPresentation() {
            var modelSpace = metamodelService.getModelSpace(SCHEMA);
            chatService.stream(
                    ChatRequest.usingData(SCHEMA, modelSpace)
                        .withThinkingLevel(ThinkingLevel.HIGH)
                        .withSystemPrompt(GENERATOR_SYSTEM)
                        .withModelName("claude-opus-4-6")
                        .withChatId("conv-dump-equipment-aging")
                        .withCachingStrategyFunction(GENERATOR_REBUTTAL_STRATEGY)
                        .ask(GENERATOR_REBUTTAL_USER.replace(
                            "{{FINDINGS}}",
                            SAMPLE_GENERATOR_SCEPTIC
                        ))
                )
                .doOnError(e -> System.err.println("Error during chat: " + e.getMessage()))
                .doOnNext(t -> {
                    System.out.print(t);
                    System.out.flush();
                })
                .blockLast();
        }

        public void runExecutor() {
            var modelSpace = metamodelService.getModelSpace(SCHEMA);
            var rareEvents = SAMPLE_GENERATOR_REVISED.substring(
                0,
                SAMPLE_GENERATOR_REVISED.indexOf("-- H1 HYPOTHESIS START")
            );
            var belowDetection = SAMPLE_GENERATOR_REVISED.substring(
                SAMPLE_GENERATOR_REVISED.indexOf("-- H3 HYPOTHESIS END") + "-- H3 HYPOTHESIS END".length()
            );
            var h2 = SAMPLE_GENERATOR_REVISED.substring(
                SAMPLE_GENERATOR_REVISED.indexOf("-- H1 HYPOTHESIS END") + "-- H1 HYPOTHESIS END".length(),
                SAMPLE_GENERATOR_REVISED.indexOf("-- H2 HYPOTHESIS END")
            );
            var singleHypothesis = rareEvents + h2 + belowDetection;
            chatService.stream(
                    ChatRequest.usingData(SCHEMA, modelSpace)
                        .withThinkingLevel(ThinkingLevel.HIGH)
                        .withToolGroups(ToolGroup.QUERY)
                        .withSystemPrompt(EXECUTOR_COMPILER_SYSTEM)
                        .withModelName("claude-opus-4-6")
                        .withCachingStrategyFunction(EXECUTOR_COMPILER_CACHE_STRATEGY)
                        .ask(EXECUTOR_COMPILER_USER.replace(
                            "{{HYPOTHESIS_SPEC}}",
                            singleHypothesis
                        ).replace(
                            "{{DOMAIN_KNOWLEDGE}}",
                            SAMPLE_DOMAIN_RESEARCH
                        ))
                )
                .doOnError(e -> System.err.println("Error during chat: " + e.getMessage()))
                .doOnNext(t -> {
                    System.out.print(t);
                    System.out.flush();
                })
                .blockLast();
        }

        @SneakyThrows
        public void runH1Pipeline() {
            var modelSpace = metamodelService.getModelSpace(SCHEMA);
            var spec = SamplePipelineSpecs.h1(objectMapper);
            var request = pipelineSpecConverter.convert(
                spec, "H1: containerInsulationType causal effect on excursionFlag", modelSpace, SCHEMA);
            var event = mlService.submit(request).await();
            System.out.printf("H1 completed: %s – %s%n%s", event.eventType(), event.message(), objectMapper.writeValueAsString(event));
        }

        @SneakyThrows
        public void runH2Pipeline() {
            var modelSpace = metamodelService.getModelSpace(SCHEMA);
            var spec = SamplePipelineSpecs.h2(objectMapper);
            var request = pipelineSpecConverter.convert(
                spec, "H2: vehicleEquipmentCohort causal effect on excursionFlag", modelSpace, SCHEMA);
            var event = mlService.submit(request).await();
            System.out.printf("H2 completed: %s – %s%n%s", event.eventType(), event.message(), objectMapper.writeValueAsString(event));
        }

        @SneakyThrows
        public void runH3Pipeline() {
            var modelSpace = metamodelService.getModelSpace(SCHEMA);
            var spec = SamplePipelineSpecs.h3(objectMapper);
            var request = pipelineSpecConverter.convert(
                spec, "H3: nodeRefrigHealthPct causal effect on excursionFlag", modelSpace, SCHEMA);
            var event = mlService.submit(request).await();
            System.out.printf("H3 completed: %s – %s%n%s", event.eventType(), event.message(), objectMapper.writeValueAsString(event));
        }

        public void runFP() {
            var modelSpace = metamodelService.getModelSpace(SCHEMA);
            var h1Start = SAMPLE_GENERATOR_REVISED.indexOf("-- H1 HYPOTHESIS START");
            var h1End = SAMPLE_GENERATOR_REVISED.indexOf("-- H1 HYPOTHESIS END");
            var singleHypothesis = SAMPLE_GENERATOR_REVISED.substring(
                h1Start + "-- H1 HYPOTHESIS START".length(),
                h1End
            ).trim();
            chatService.stream(
                    ChatRequest.usingData(SCHEMA, modelSpace)
                        .withThinkingLevel(ThinkingLevel.HIGH)
                        .withSystemPrompt(FORENSIC_PATHOLOGIST_SYSTEM)
                        .withModelName("claude-opus-4-6")
                        .withCachingStrategyFunction(FORENSIC_PATHOLOGIST_CACHE_STRATEGY)
                        .ask(FORENSIC_PATHOLOGIST_USER.replace(
                            "{{HYPOTHESIS_SPEC}}",
                            singleHypothesis
                        ).replace(
                            "{{DOMAIN_KNOWLEDGE}}",
                            SAMPLE_DOMAIN_RESEARCH
                        ).replace(
                            "{{PIPELINE_OUTPUT}}",
                            SAMPLE_H1_PIPELINE_OUTPUT
                        ))
                )
                .doOnError(e -> System.err.println("Error during chat: " + e.getMessage()))
                .doOnNext(t -> {
                    System.out.print(t);
                    System.out.flush();
                })
                .blockLast();
        }

        public void runReconPhase() {
            inScope("containers", () -> {
                new ReconPhase(config).execute();
                return null;
            });
        }

        /**
         * Runs {@code body} with {@link SwarmScope#CTX} and
         * {@link SwarmScope#ANCHOR_TAG} bound around a live
         * {@link SwarmEventFormatter}. Any phase executed inside the
         * supplier can read ctx/anchorTag from scope instead of taking
         * them as parameters.
         */
        private <T> T inScope(String anchorTag, java.util.function.Supplier<T> body) {
            try (var formatter = new SwarmEventFormatter()) {
                var ctx = createCtx(formatter.events());
                return ScopedValue.where(SwarmScope.CTX, ctx)
                    .where(SwarmScope.ANCHOR_TAG, anchorTag)
                    .call(body::get);
            }
        }

        private SwarmRunContext createCtx(Sinks.Many<SwarmEvent> events) {
            var modelSpace = metamodelService.getModelSpace(SCHEMA);
            var input = new SwarmInput(
                "How can I decrease excursion rates", SCHEMA, modelSpace, List.of(SAMPLE_ANCHOR));
            var summarizer = new SecondarySwarmAgent(
                config.summarizer(), chatService, SCHEMA, modelSpace, promptPlaceholders);
            return new SwarmRunContext(
                input, StepJournal.current(), events, summarizer, chatService, promptPlaceholders);
        }

        public GenPhase.Output runGenPhase() {
            return inScope("containers", () -> new GenPhase(config).execute(
                SAMPLE_ANCHOR, List.of(), SAMPLE_SURVEY, SAMPLE_DOMAIN_RESEARCH));
        }

        public void runCompilePhase() {
            inScope("containers", () -> {
                var rebuttalId = EventId.root("rebuttal", StepJournal.current().randomUUID());
                var specText = extractHypothesisSpec("H2");
                new CompilePhase(config, pipelineSpecConverter, mlService)
                    .execute(rebuttalId, "H2", specText, SAMPLE_DOMAIN_RESEARCH);
                return null;
            });
        }

        private static String extractHypothesisSpec(String id) {
            var startTag = "-- " + id + " HYPOTHESIS START";
            var endTag = "-- " + id + " HYPOTHESIS END";
            return SAMPLE_GENERATOR_REVISED.substring(
                SAMPLE_GENERATOR_REVISED.indexOf(startTag) + startTag.length(),
                SAMPLE_GENERATOR_REVISED.indexOf(endTag)
            ).trim();
        }

        /**
         * Runs {@link CompilePhase} + pipeline for every hypothesis in the
         * given gen output. The rebuttal's globals block is sliced once
         * from the raw rebuttal text and reused across the fanout.
         */
        public void runCompilePipelineFromGen(GenPhase.Output gen) {
            inScope("containers", () -> {
                var rebuttalRaw = gen.rebuttal().rawResponse();
                var rebuttalId = gen.rebuttal().id();
                var rebuttalDto = gen.rebuttal().dto();
                var globals = rebuttalDto.renderGlobalsFromRaw(rebuttalRaw);
                var compiler = new CompilePhase(config, pipelineSpecConverter, mlService);
                for (var h : rebuttalDto.hypotheses()) {
                    var specBlock = h.sliceSpec(rebuttalRaw);
                    var combined = globals.isEmpty() ? specBlock : specBlock + "\n\n" + globals;
                    compiler.execute(rebuttalId, h.id(), combined, SAMPLE_DOMAIN_RESEARCH);
                }
                return null;
            });
        }

        /**
         * Runs {@link GenPhase} and then fans out {@link CompilePhase} +
         * pipeline over every resulting hypothesis. Equivalent to
         * {@code runCompilePipelineFromGen(runGenPhase())} but written out
         * explicitly so the sequence is visible in one place.
         */
        public void runGenCompilePipeline() {
            runCompilePipelineFromGen(runGenPhase());
        }

        @SneakyThrows
        public void runNullPhase() {
            var pipelineResult = objectMapper.readValue(SAMPLE_H1_PIPELINE_OUTPUT, JobEvent.class);
            inScope("containers", () -> {
                var compilerId = EventId.root("compiler", StepJournal.current().randomUUID());
                var specText = extractHypothesisSpec("H1");
                new NullPhase(config, objectMapper)
                    .execute(compilerId, "H1", specText, SAMPLE_DOMAIN_RESEARCH, pipelineResult);
                return null;
            });
        }

    }
}
