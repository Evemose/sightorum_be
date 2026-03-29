package com.rorm.client.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.DurableRuntime;
import com.rorm.JobSpec;
import com.rorm.ai.chat.*;
import com.rorm.client.ai.AiChatRunner.AgentJP;
import com.rorm.client.metamodel.MetamodelService;
import com.rorm.ml.MlTrainingService;
import com.rorm.ml.PipelineSpecConverter;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

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

    @Test
    void simpleChat() throws Exception {
        Thread.sleep(10 * 60 * 1000);
        //noinspection ConstantValue
        if (true) { // guard from accidental execution
            durableRuntime.submit("runner-pipeline-h1-v1_1", new JobSpec(
                "agentJp",
                "runH1Pipeline"
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
                        .withToolGroups(ToolGroup.WEB_ACCESS, ToolGroup.QUERY, ToolGroup.DATA_RELATIONS)
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
                        .withToolGroups(ToolGroup.WEB_ACCESS, ToolGroup.QUERY)
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
            var singleHypothesis = SAMPLE_GENERATOR_REVISED.substring(
                0,
                SAMPLE_GENERATOR_REVISED.indexOf("--TRIM_AFTER")
            ) + SAMPLE_GENERATOR_REVISED.substring(
                SAMPLE_GENERATOR_REVISED.indexOf("--RESTORE-AFTER") + "--RESTORE-AFTER".length()
            );
            chatService.stream(
                    ChatRequest.usingData(SCHEMA, modelSpace)
                        .withThinkingLevel(ThinkingLevel.HIGH)
                        .withToolGroups(ToolGroup.WEB_ACCESS, ToolGroup.QUERY)
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

        public void runH1Pipeline() {
            var modelSpace = metamodelService.getModelSpace(SCHEMA);
            var spec = SamplePipelineSpecs.h1(objectMapper);
            var request = pipelineSpecConverter.convert(
                spec, "H1: containerInsulationType causal effect on excursionFlag", modelSpace, SCHEMA);
            var event = mlService.submit(request).join();
            System.out.printf("H1 completed: %s – %s%n", event.eventType(), event.message());
        }

        public void runH3Pipeline() {
            var modelSpace = metamodelService.getModelSpace(SCHEMA);
            var spec = SamplePipelineSpecs.h3(objectMapper);
            var request = pipelineSpecConverter.convert(
                spec, "H3: nodeRefrigHealthPct causal effect on excursionFlag", modelSpace, SCHEMA);
            var event = mlService.submit(request).join();
            System.out.printf("H3 completed: %s – %s%n", event.eventType(), event.message());
        }
    }
}
