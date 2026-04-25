package com.rorm.client.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.DurableRuntime;
import com.rorm.JobSpec;
import com.rorm.StepJournal;
import com.rorm.ai.chat.*;
import com.rorm.ai.swarm.*;
import com.rorm.ai.swarm.phase.*;
import com.rorm.client.ai.AiChatRunner.AgentJP;
import com.rorm.client.metamodel.MetamodelService;
import com.rorm.ml.MlTrainingService;
import com.rorm.ml.PipelineSpecConverter;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

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
            var submit = durableRuntime.submit("runner-genphase-6", new JobSpec(
                "agentJp",
                "runGenPhase"
            ));
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(System.out, submit);
            durableRuntime.submit("runner-compile-from-genphase-4", new JobSpec(
                "agentJp",
                "runCompilePipelineFromGen",
                new Object[]{submit},
                new String[]{GenPhase.Output.class.getName()}
            ));
        }
    }

    @Test
    void h3() throws Exception {
        //noinspection ConstantValue
        if (true) { // guard from accidental execution
            var res = durableRuntime.submit("runner-h3-pipeline", new JobSpec(
                "agentJp",
                "runH3Pipeline"
            ));
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(System.out, res);
        }
    }

    @Test
    void compileH3() throws Exception {
        //noinspection ConstantValue
        if (true) {
            var res = durableRuntime.submit("runner-compile-h3-4", new JobSpec(
                "agentJp",
                "runCompilePhaseH3"
            ));
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(System.out, res);
        }
    }

    @Test
    void standoffH3() throws Exception {
        //noinspection ConstantValue
        if (true) { // guard from accidental execution
            var res = durableRuntime.submit("runner-standoff-h3-1", new JobSpec(
                "agentJp",
                "runStandoffH3"
            ));
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(System.out, res);
        }
    }

    @Test
    void descPhase() throws Exception {
        //noinspection ConstantValue
        if (false) { // guard from accidental execution
            var res = durableRuntime.submit("runner-descphase-2", new JobSpec(
                "agentJp",
                "runDescPhase"
            ));
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(System.out, res);
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
        private ReconPhase reconPhase;
        @Autowired
        private GenPhase genPhase;
        @Autowired
        private CompilePhase compilePhase;
        @Autowired
        private NullPhase nullPhase;
        @Autowired
        private DescPhase descPhase;
        @Autowired
        private StandoffPhase standoffPhase;
        @Autowired
        private org.springframework.core.io.ResourceLoader resourceLoader;
        @Autowired
        private SwarmEventBus eventBus;

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
                        .withToolGroups(ToolGroup.QUERY, ToolGroup.STATS, ToolGroup.DATA_RELATIONS)
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

        public void runScout() {
            var modelSpace = metamodelService.getModelSpace(SCHEMA);
            var scoutUser = SwarmPrompts.SURVEY_SCOUT_USER.replace(
                "{{USER_QUERY}}",
                "How can I decrease excursion rates"
            );
            chatService.stream(
                    ChatRequest.usingData(SCHEMA, modelSpace)
                        .withToolGroups(ToolGroup.QUERY, ToolGroup.STATS)
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
                        .withToolGroups(ToolGroup.QUERY, ToolGroup.STATS, ToolGroup.VERIFICATION)
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
                        .withToolGroups(ToolGroup.QUERY, ToolGroup.STATS)
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
            withFormatter(runId -> reconPhase.run(buildInput(), runId));
        }

        private <T> T withFormatter(Function<String, T> phase) {
            var runId = newRunId();
            try (var _ = new SwarmEventFormatter(eventBus.subscribe(runId))) {
                try {
                    return phase.apply(runId);
                } finally {
                    eventBus.complete(runId);
                }
            }
        }

        private SwarmInput buildInput() {
            var modelSpace = metamodelService.getModelSpace(SCHEMA);
            return new SwarmInput(
                "How can I decrease excursion rates", SCHEMA, modelSpace, List.of(SAMPLE_ANCHOR));
        }

        private String newRunId() {
            return "runner-" + StepJournal.current().randomUUID();
        }

        public DescPhase.Output runDescPhase() {
            return withFormatter(runId -> descPhase.run(buildInput(), runId));
        }

        public void runCompilePhase() {
            withFormatter(runId -> {
                var input = buildInput();
                var recon = reconPhase.run(input, runId);
                var anchor = buildAnchor(input, recon);
                var gen = genPhase.run(anchor, runId);
                var first = gen.rebuttal().dto().hypotheses().getFirst();
                return compilePhase.run(new HypothesisContext(anchor, gen, first.title()), runId);
            });
        }

        private AnchorContext buildAnchor(SwarmInput input, ReconPhase.Output recon) {
            return new AnchorContext(input, SAMPLE_ANCHOR, "containers", recon);
        }

        public CompilePhase.Output runCompilePhaseH3() {
            return withFormatter(runId -> {
                var anchor = new AnchorContext(buildInput(), SAMPLE_ANCHOR, "containers", buildFakeRecon());
                var gen = buildFakeGen();
                return compilePhase.run(new HypothesisContext(anchor, gen, "H3"), runId);
            });
        }

        public void runGenCompilePipeline() {
            runCompilePipelineFromGen(runGenPhase());
        }

        private ReconPhase.Output buildFakeRecon() {
            return new ReconPhase.Output(
                new StepOutput<>(EventId.root("scout", StepJournal.current().randomUUID()), null, SAMPLE_SURVEY),
                new StepOutput<>(EventId.root("domain-researcher", StepJournal.current().randomUUID()), null, SAMPLE_DOMAIN_RESEARCH));
        }

        private GenPhase.Output buildFakeGen() {
            var rebuttalId = EventId.root("rebuttal", UUID.randomUUID());
            return new GenPhase.Output(
                "fake-chat-id",
                new StepOutput<>(EventId.root("generator", UUID.randomUUID()), null, ""),
                new StepOutput<>(EventId.root("sceptic", UUID.randomUUID()), null, ""),
                new StepOutput<>(rebuttalId, null, SAMPLE_GENERATOR_REVISED));
        }

        public List<CompilePhase.Output> runCompilePipelineFromGen(GenPhase.Output gen) {
            return withFormatter(runId -> {
                var anchor = new AnchorContext(buildInput(), SAMPLE_ANCHOR, "containers", buildFakeRecon());
                return StepJournal.current().fanout(
                    "compile-phase-fanout",
                    CompilePhase.Output.class,
                    gen.rebuttal().dto().hypotheses().stream()
                        .<Supplier<CompilePhase.Output>>map(h -> () -> compilePhase.run(
                            new HypothesisContext(anchor, gen, h.title()), runId))
                        .toList());
            });
        }

        public GenPhase.Output runGenPhase() {
            return withFormatter(runId -> genPhase.run(buildAnchor(buildInput(), buildFakeRecon()), runId));
        }

        @SneakyThrows
        public StandoffPhase.Output runStandoffH3() {
            var resource = resourceLoader.getResource(H3_WITH_SCEPTIC_LATEST_FILE_PATH);
            CompilePhase.Output compile;
            try (var in = resource.getInputStream()) {
                compile = objectMapper.readValue(in, CompilePhase.Output.class);
            }
            var anchor = new AnchorContext(buildInput(), SAMPLE_ANCHOR, "containers", buildFakeRecon());
            var gen = buildFakeGen();
            var hypoCtx = new HypothesisContext(anchor, gen, "H3");
            var pipeCtx = new PipelineContext(hypoCtx, compile);
            return withFormatter(runId -> standoffPhase.run(pipeCtx, runId));
        }

    }
}
