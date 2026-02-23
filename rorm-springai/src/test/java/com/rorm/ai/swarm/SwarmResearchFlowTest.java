package com.rorm.ai.swarm;

import com.rorm.ai.chat.AiChatService;
import com.rorm.ai.chat.ChatRequest;
import com.rorm.ai.swarm.SwarmEvent.*;
import com.rorm.ai.swarm.dto.*;
import com.rorm.metamodel.ModelSpace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.ai.vectorstore.VectorStore;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("Swarm Research Flow")
@Execution(ExecutionMode.SAME_THREAD)
class SwarmResearchFlowTest {

    @Test
    @DisplayName("runs research end-to-end and emits full ordered event + token stream contract")
    void runsResearchEndToEnd() {
        var chatService = mock(AiChatService.class);
        var modelSpace = mock(ModelSpace.class);
        var vectorStore = mock(VectorStore.class);

        var scoutOverview = sampleScoutOverview();
        var plan = samplePlan();
        var planCritique = samplePlanCritique();
        var stepResult = sampleStepResult();
        var branchResult = sampleBranchResult(stepResult);
        var analysis = sampleAnalysisResult();
        var analysisCritique = sampleAnalysisCritique();

        stubStreaming(chatService);
        stubStructuring(chatService, scoutOverview, plan, planCritique, stepResult, branchResult, analysis, analysisCritique);

        var swarm = new Swarm(new SwarmConfig(null, null, null, null, null, null), chatService, "test_schema", modelSpace, vectorStore);

        var events = swarm.research("How did churn evolve?")
            .collectList()
            .block(Duration.ofSeconds(10));

        assertThat(events).isNotNull();
        assertThat(events)
            .extracting(Object::getClass)
            .containsExactly(
                ScoutStarted.class,
                ScoutFinished.class,
                PlanNegotiationStarted.class,
                PlanVersionCreationStarted.class,
                PlanVersionCreationFinished.class,
                PlanVersionCritiqueStarted.class,
                PlanVersionCritiqueFinished.class,
                PlanNegotiationFinished.class,
                BranchExecutionStarted.class,
                StepExecutionStarted.class,
                StepExecutionFinished.class,
                BranchExecutionFinished.class,
                AnalysisNegotiationStarted.class,
                AnalysisVersionCreationStarted.class,
                AnalysisVersionCreationFinished.class,
                AnalysisVersionCritiqueStarted.class,
                AnalysisVersionCritiqueFinished.class,
                AnalysisNegotiationFinished.class
            );

        assertThat(((ScoutStarted) events.get(0)).tokenStream().collectList().block())
            .containsExactly("", "scout-1", "scout-1scout-2");

        assertThat(((PlanVersionCreationStarted) events.get(3)).tokenStream().collectList().block())
            .containsExactly(
                "Iteration 1:\nPlanner:\n\n",
                "",
                "plan-1",
                "plan-1plan-2",
                "\nCritic:\n\n",
                "",
                "plan-crit-1",
                "plan-crit-1plan-crit-2"
            );
        assertThat(((PlanVersionCritiqueStarted) events.get(5)).tokenStream().collectList().block())
            .containsExactly(
                "Iteration 1:\nPlanner:\n\n",
                "",
                "plan-1",
                "plan-1plan-2",
                "\nCritic:\n\n",
                "",
                "plan-crit-1",
                "plan-crit-1plan-crit-2"
            );
        assertThat(((PlanNegotiationStarted) events.get(2)).tokenStream().collectList().block())
            .containsExactly(
                "Iteration 1:\nPlanner:\n\n",
                "",
                "plan-1",
                "plan-1plan-2",
                "\nCritic:\n\n",
                "",
                "plan-crit-1",
                "plan-crit-1plan-crit-2"
            );

        assertThat(((StepExecutionStarted) events.get(9)).tokenStream().collectList().block())
            .containsExactly("", "step-1", "step-1step-2");
        assertThat(((BranchExecutionStarted) events.get(8)).tokenStream().collectList().block())
            .containsExactly("", "step-1", "step-1step-2");

        assertThat(((AnalysisVersionCreationStarted) events.get(13)).tokenStream().collectList().block())
            .containsExactly(
                "Iteration 1:\nAnalyzer:\n\n",
                "",
                "analysis-1",
                "analysis-1analysis-2",
                "\nCritic:\n\n",
                "",
                "analysis-crit-1",
                "analysis-crit-1analysis-crit-2"
            );
        assertThat(((AnalysisVersionCritiqueStarted) events.get(15)).tokenStream().collectList().block())
            .containsExactly(
                "Iteration 1:\nAnalyzer:\n\n",
                "",
                "analysis-1",
                "analysis-1analysis-2",
                "\nCritic:\n\n",
                "",
                "analysis-crit-1",
                "analysis-crit-1analysis-crit-2"
            );
        assertThat(((AnalysisNegotiationStarted) events.get(12)).tokenStream().collectList().block())
            .containsExactly(
                "Iteration 1:\nAnalyzer:\n\n",
                "",
                "analysis-1",
                "analysis-1analysis-2",
                "\nCritic:\n\n",
                "",
                "analysis-crit-1",
                "analysis-crit-1analysis-crit-2"
            );

        var scoutFinished = (ScoutFinished) events.get(1);
        assertThat(scoutFinished.findings()).isEqualTo(scoutOverview);
        assertThat(scoutFinished.rawResponse()).isEqualTo("scout-1scout-2");

        var planVersionCreationFinished = (PlanVersionCreationFinished) events.get(4);
        assertThat(planVersionCreationFinished.findings()).isEqualTo(plan);
        assertThat(planVersionCreationFinished.rawResponse()).isEqualTo("plan-1plan-2");

        var planVersionCritiqueFinished = (PlanVersionCritiqueFinished) events.get(6);
        assertThat(planVersionCritiqueFinished.findings()).isEqualTo(planCritique);
        assertThat(planVersionCritiqueFinished.rawResponse()).isEqualTo("plan-crit-1plan-crit-2");

        var planNegotiationFinished = (PlanNegotiationFinished) events.get(7);
        assertThat(planNegotiationFinished.findings()).isEqualTo(plan);
        assertThat(planNegotiationFinished.finishReason()).isEqualTo(NegotiationFinishReason.APPROVED);
        assertThat(planNegotiationFinished.rawResponse())
            .contains("Iteration 1:\nPlanner:\n\n")
            .contains("plan-1plan-2")
            .contains("\nCritic:\n\n")
            .contains("plan-crit-1plan-crit-2");

        var stepExecutionStarted = (StepExecutionStarted) events.get(9);
        assertThat(stepExecutionStarted.previousStepId()).isNull();
        assertThat(stepExecutionStarted.dependencies()).isEmpty();

        var stepExecutionFinished = (StepExecutionFinished) events.get(10);
        assertThat(stepExecutionFinished.findings()).isEqualTo(stepResult);
        assertThat(stepExecutionFinished.rawResponse()).isEqualTo("step-1step-2");
        assertThat(stepExecutionFinished.previousStepId()).isNull();
        assertThat(stepExecutionFinished.dependencies()).isEmpty();

        var branchExecutionFinished = (BranchExecutionFinished) events.get(11);
        assertThat(branchExecutionFinished.findings()).isEqualTo(branchResult);
        assertThat(branchExecutionFinished.rawResponse()).isEqualTo("Branch execution completed");

        var analysisVersionCreationFinished = (AnalysisVersionCreationFinished) events.get(14);
        assertThat(analysisVersionCreationFinished.findings()).isEqualTo(analysis);
        assertThat(analysisVersionCreationFinished.rawResponse()).isEqualTo("analysis-1analysis-2");

        var analysisVersionCritiqueFinished = (AnalysisVersionCritiqueFinished) events.get(16);
        assertThat(analysisVersionCritiqueFinished.findings()).isEqualTo(analysisCritique);
        assertThat(analysisVersionCritiqueFinished.rawResponse()).isEqualTo("analysis-crit-1analysis-crit-2");

        var analysisNegotiationFinished = (AnalysisNegotiationFinished) events.get(17);
        assertThat(analysisNegotiationFinished.findings()).isEqualTo(analysis);
        assertThat(analysisNegotiationFinished.reason()).isEqualTo(NegotiationFinishReason.APPROVED);
        assertThat(analysisNegotiationFinished.rawResponse()).isEqualTo("Analysis negotiation completed");

        verify(vectorStore, timeout(2_000)).add(any(List.class));
    }

    private ScoutOverviewDTO sampleScoutOverview() {
        return new ScoutOverviewDTO(
            List.of(new ScoutOverviewDTO.EntitySummary("customers", "Customer records", "1000", List.of("id"), List.of())),
            List.of(),
            List.of("pattern"),
            List.of("recommendation"),
            ScoutOverviewDTO.ComplexityLevel.MEDIUM,
            List.of("constraint"),
            Instant.parse("2026-02-21T10:00:00Z")
        );
    }

    private ResearchPlanDTO samplePlan() {
        var step = new ResearchPlanDTO.ResearchStep(
            "step-1",
            "Measure churn",
            "Aggregate churn",
            List.of(),
            List.of()
        );
        var branch = new ResearchPlanDTO.ResearchBranch(
            "branch-1",
            "Analyze churn",
            List.of(step),
            3,
            ResearchPlanDTO.Priority.HIGH
        );
        return new ResearchPlanDTO(
            "Understand churn",
            List.of(branch),
            3,
            List.of("have conclusion"),
            Instant.parse("2026-02-21T10:05:00Z")
        );
    }

    private PlanCritiqueDTO samplePlanCritique() {
        return new PlanCritiqueDTO(
            List.of(),
            List.of("good decomposition"),
            9.0,
            "Looks good",
            List.of(),
            PlanCritiqueDTO.RiskLevel.LOW,
            Instant.parse("2026-02-21T10:06:00Z")
        );
    }

    private StepExecutionResultDTO sampleStepResult() {
        return new StepExecutionResultDTO(
            "Step summary",
            "Step insight",
            "Step details",
            List.of(new StepExecutionResultDTO.ResearchActionDTO("why", "what", "observed")),
            Map.of("var1", "value1"),
            Instant.parse("2026-02-21T10:10:00Z")
        );
    }

    private BranchExecutionResultDTO sampleBranchResult(StepExecutionResultDTO stepResult) {
        return new BranchExecutionResultDTO(
            "Analyze churn",
            "Branch summary",
            List.of("insight"),
            List.of(stepResult),
            Map.of("var1", "value1"),
            Instant.parse("2026-02-21T10:12:00Z")
        );
    }

    private AnalysisResultDTO sampleAnalysisResult() {
        return new AnalysisResultDTO(
            "Main conclusion",
            List.of(new AnalysisResultDTO.Evidence("branch-1", "finding", AnalysisResultDTO.EvidenceStrength.STRONG)),
            8.9,
            List.of("alternative"),
            List.of(new AnalysisResultDTO.ResearchGap("gap", AnalysisResultDTO.GapImpact.LOW, "none")),
            false,
            List.of(),
            List.of("assumption"),
            Instant.parse("2026-02-21T10:15:00Z")
        );
    }

    private ConclusionCritiqueDTO sampleAnalysisCritique() {
        return new ConclusionCritiqueDTO(
            9.1,
            List.of(),
            List.of("sound synthesis"),
            "Looks good",
            List.of(),
            ConclusionCritiqueDTO.RiskLevel.LOW,
            Instant.parse("2026-02-21T10:16:00Z")
        );
    }

    private void stubStreaming(AiChatService chatService) {
        var streamCall = new AtomicInteger();
        when(chatService.stream(any(ChatRequest.class))).thenAnswer(_ -> switch (streamCall.incrementAndGet()) {
            case 1 -> Flux.just("scout-1", "scout-2");
            case 2 -> Flux.just("plan-1", "plan-2");
            case 3 -> Flux.just("plan-crit-1", "plan-crit-2");
            case 4 -> Flux.just("step-1", "step-2");
            case 5 -> Flux.just("analysis-1", "analysis-2");
            case 6 -> Flux.just("analysis-crit-1", "analysis-crit-2");
            default -> throw new IllegalStateException("Unexpected stream call index");
        });
    }

    @SuppressWarnings("unchecked")
    private void stubStructuring(
        AiChatService chatService,
        ScoutOverviewDTO scoutOverview,
        ResearchPlanDTO plan,
        PlanCritiqueDTO planCritique,
        StepExecutionResultDTO stepResult,
        BranchExecutionResultDTO branchResult,
        AnalysisResultDTO analysis,
        ConclusionCritiqueDTO analysisCritique
    ) {
        when(chatService.call(any(ChatRequest.class))).thenAnswer(invocation -> {
            ChatRequest<?> request = invocation.getArgument(0);
            if (request.responseType() == ScoutOverviewDTO.class) {
                return scoutOverview;
            }
            if (request.responseType() == ResearchPlanDTO.class) {
                return plan;
            }
            if (request.responseType() == PlanCritiqueDTO.class) {
                return planCritique;
            }
            if (request.responseType() == StepExecutionResultDTO.class) {
                return stepResult;
            }
            if (request.responseType() == BranchExecutionResultDTO.class) {
                return branchResult;
            }
            if (request.responseType() == AnalysisResultDTO.class) {
                return analysis;
            }
            if (request.responseType() == ConclusionCritiqueDTO.class) {
                return analysisCritique;
            }
            throw new IllegalStateException("Unexpected response type: " + request.responseType());
        });
    }
}
