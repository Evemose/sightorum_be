package com.rorm.client.ai;

import com.rorm.DurableRuntime;
import com.rorm.JobSpec;
import com.rorm.ai.chat.*;
import com.rorm.client.ai.AiChatRunner.AgentJP;
import com.rorm.client.metamodel.MetamodelService;
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
    void simpleChat() {
        //noinspection ConstantValue
        if (false) { // guard from accidental execution
            durableRuntime.submit("runner-executor-v9", new JobSpec(
                "agentJp",
                "runExecutor"
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
    }
}
