package com.rorm.client.research;

import com.rorm.ai.swarm.NegotiationFinishReason;
import com.rorm.ai.swarm.SwarmEvent;
import com.rorm.ai.swarm.dto.*;
import com.rorm.ai.swarm.dto.PlanCritiqueDTO.PlanChallenge;
import com.rorm.ai.swarm.dto.PlanCritiqueDTO.PlanModification;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Mock swarm that generates fake research events for development and testing.
 */
public class MockSwarm {

    public static Flux<SwarmEvent> research(String query) {
        var eventSink = Sinks.many().replay().<SwarmEvent>all();

        Thread.ofVirtual().start(() -> {
            try {
                emitScout(eventSink, query);
                emitPlan(eventSink, query);
                emitExecution(eventSink);
                emitAnalysis(eventSink, query);
                eventSink.tryEmitComplete();
            } catch (Exception e) {
                eventSink.tryEmitError(e);
            }
        });

        return eventSink.asFlux();
    }

    private static void emitScout(Sinks.Many<SwarmEvent> sink, String query) throws InterruptedException {
        var id = "scout";
        var tokenSink = Sinks.many().unicast().<String>onBackpressureBuffer();
        sink.tryEmitNext(new SwarmEvent.ScoutStarted(id, tokenSink.asFlux()));
        Thread.sleep(3000);
        emitTokens(tokenSink, "Analyzing data landscape for: " + query + "\n\n"
                              + "Found 3 root entities: customers, orders, products.\n"
                              + "Data quality: generally good with minor missing values in customer email field.");
        Thread.sleep(3000);
        sink.tryEmitNext(new SwarmEvent.ScoutFinished(id, mockScout(), "Mock scout response"));
    }

    private static void emitPlan(Sinks.Many<SwarmEvent> sink, String query) throws InterruptedException {
        var id = "plan";
        var tokenSink = Sinks.many().unicast().<String>onBackpressureBuffer();
        sink.tryEmitNext(new SwarmEvent.PlanNegotiationStarted(id, tokenSink.asFlux()));
        Thread.sleep(2000);
        var negotiationRaw = new StringBuilder();
        var intro = "Creating research plan for: " + query + "\n\n"
                    + "Branch 1: Customer segmentation analysis\n"
                    + "Branch 2: Revenue trend analysis\n\n";
        streamTokensToSinks(intro, tokenSink);
        negotiationRaw.append(intro);

        var planV1TokenSink = Sinks.many().unicast().<String>onBackpressureBuffer();
        sink.tryEmitNext(new SwarmEvent.PlanVersionCreationStarted(id, 1, planV1TokenSink.asFlux()));
        streamTokensToSinks("Drafting plan v1 with initial steps...", tokenSink, planV1TokenSink);
        completeSink(planV1TokenSink);
        var planV1Raw = "Plan v1 drafted";
        sink.tryEmitNext(new SwarmEvent.PlanVersionCreationFinished(id, 1, mockPlanV1(), planV1Raw));
        negotiationRaw.append(planV1Raw).append("\n");

        var planV1CritiqueTokenSink = Sinks.many().unicast().<String>onBackpressureBuffer();
        sink.tryEmitNext(new SwarmEvent.PlanVersionCritiqueStarted(id, 1, planV1CritiqueTokenSink.asFlux()));
        streamTokensToSinks("Reviewing plan v1 for dependencies and risk controls...", tokenSink, planV1CritiqueTokenSink);
        completeSink(planV1CritiqueTokenSink);
        var planV1CritiqueRaw = "Plan v1 critique complete";
        sink.tryEmitNext(new SwarmEvent.PlanVersionCritiqueFinished(
            id,
            1,
            new com.rorm.ai.swarm.dto.PlanCritiqueDTO(
                List.of(
                    new PlanChallenge(
                        "customer-segmentation/analyze-segments",
                        com.rorm.ai.swarm.dto.PlanCritiqueDTO.PlanChallengeType.WRONG_DEPENDENCY,
                        "Analyze step does not explicitly depend on segmentation outputs.",
                        "Declare dependency on segment-customers output.",
                        com.rorm.ai.swarm.dto.PlanCritiqueDTO.Severity.HIGH
                    ),
                    new PlanChallenge(
                        "revenue-trends",
                        com.rorm.ai.swarm.dto.PlanCritiqueDTO.PlanChallengeType.INCOMPLETE_DECOMPOSITION,
                        "Revenue trend branch lacks metric stability validation.",
                        "Add follow-up validation step for trend robustness.",
                        com.rorm.ai.swarm.dto.PlanCritiqueDTO.Severity.MEDIUM
                    )
                ),
                List.of("Clear branch goals"),
                6.8,
                "Revision required",
                List.of(
                    new PlanModification(
                        com.rorm.ai.swarm.dto.PlanCritiqueDTO.ModificationType.REORDER_DEPENDENCIES,
                        "customer-segmentation/analyze-segments",
                        "Use outputs from segment-customers as required input."
                    ),
                    new PlanModification(
                        com.rorm.ai.swarm.dto.PlanCritiqueDTO.ModificationType.MODIFY_STEP,
                        "revenue-trends",
                        "Add validation step for trend stability."
                    )
                ),
                com.rorm.ai.swarm.dto.PlanCritiqueDTO.RiskLevel.MEDIUM,
                Instant.now()
            ),
            planV1CritiqueRaw
        ));
        negotiationRaw.append(planV1CritiqueRaw).append("\n");

        var planV2TokenSink = Sinks.many().unicast().<String>onBackpressureBuffer();
        sink.tryEmitNext(new SwarmEvent.PlanVersionCreationStarted(id, 2, planV2TokenSink.asFlux()));
        streamTokensToSinks("Updating plan to v2 with dependency and validation fixes...", tokenSink, planV2TokenSink);
        completeSink(planV2TokenSink);
        var finalPlan = mockPlanV2();
        var planV2Raw = "Plan v2 drafted";
        sink.tryEmitNext(new SwarmEvent.PlanVersionCreationFinished(id, 2, finalPlan, planV2Raw));
        negotiationRaw.append(planV2Raw).append("\n");

        var planV2CritiqueTokenSink = Sinks.many().unicast().<String>onBackpressureBuffer();
        sink.tryEmitNext(new SwarmEvent.PlanVersionCritiqueStarted(id, 2, planV2CritiqueTokenSink.asFlux()));
        streamTokensToSinks("Reviewing plan v2...", tokenSink, planV2CritiqueTokenSink);
        completeSink(planV2CritiqueTokenSink);
        var planV2CritiqueRaw = "Plan v2 critique complete";
        sink.tryEmitNext(new SwarmEvent.PlanVersionCritiqueFinished(
            id,
            2,
            new com.rorm.ai.swarm.dto.PlanCritiqueDTO(
                List.of(),
                List.of(
                    "Dependencies are explicit and traceable",
                    "Validation step reduces metric reliability risk"
                ),
                9.0,
                "Plan approved",
                List.of(),
                com.rorm.ai.swarm.dto.PlanCritiqueDTO.RiskLevel.LOW,
                Instant.now()
            ),
            planV2CritiqueRaw
        ));
        negotiationRaw.append(planV2CritiqueRaw);

        Thread.sleep(2000);
        completeSink(tokenSink);
        sink.tryEmitNext(new SwarmEvent.PlanNegotiationFinished(id, finalPlan, negotiationRaw.toString(), NegotiationFinishReason.APPROVED));
    }

    private static void emitExecution(Sinks.Many<SwarmEvent> sink) throws InterruptedException {
        var branchId1 = "customer-segmentation";
        var branchTokenSink1 = Sinks.many().unicast().<String>onBackpressureBuffer();
        sink.tryEmitNext(new SwarmEvent.BranchExecutionStarted(branchId1, branchTokenSink1.asFlux()));
        var branch1Raw = new StringBuilder();
        var branch1Intro = "Starting customer segmentation branch...";
        streamTokensToSinks(branch1Intro, branchTokenSink1);
        branch1Raw.append(branch1Intro).append("\n");

        var stepTokenSink1 = Sinks.many().unicast().<String>onBackpressureBuffer();
        sink.tryEmitNext(new SwarmEvent.StepExecutionStarted(branchId1, "segment-customers", null, List.of(), stepTokenSink1.asFlux()));
        Thread.sleep(3000);
        streamTokensToSinks("Segmenting customers by purchase frequency and value...", branchTokenSink1, stepTokenSink1);
        completeSink(stepTokenSink1);
        Thread.sleep(3000);
        var step1Raw = "Mock step response: segment-customers";
        sink.tryEmitNext(new SwarmEvent.StepExecutionFinished(branchId1, "segment-customers", null, List.of(), mockStepResult("Segmented customers into 4 groups"), step1Raw));
        branch1Raw.append(step1Raw).append("\n");

        var stepTokenSink2 = Sinks.many().unicast().<String>onBackpressureBuffer();
        sink.tryEmitNext(new SwarmEvent.StepExecutionStarted(branchId1, "analyze-segments", "segment-customers", List.of(new StepRef(branchId1, "segment-customers")), stepTokenSink2.asFlux()));
        Thread.sleep(3000);
        streamTokensToSinks("Analyzing segment characteristics...", branchTokenSink1, stepTokenSink2);
        completeSink(stepTokenSink2);
        Thread.sleep(3000);
        var step2Raw = "Mock step response: analyze-segments";
        sink.tryEmitNext(new SwarmEvent.StepExecutionFinished(branchId1, "analyze-segments", "segment-customers", List.of(new StepRef(branchId1, "segment-customers")), mockStepResult("VIP segment drives 62% of revenue"), step2Raw));
        branch1Raw.append(step2Raw);

        completeSink(branchTokenSink1);
        sink.tryEmitNext(new SwarmEvent.BranchExecutionFinished(branchId1, mockBranchResult("Customer segmentation reveals VIP concentration"), branch1Raw.toString()));
        Thread.sleep(1000);

        var branchId2 = "revenue-trends";
        var branchTokenSink2 = Sinks.many().unicast().<String>onBackpressureBuffer();
        sink.tryEmitNext(new SwarmEvent.BranchExecutionStarted(branchId2, branchTokenSink2.asFlux()));
        var branch2Raw = new StringBuilder();
        var branch2Intro = "Starting revenue trend analysis...";
        streamTokensToSinks(branch2Intro, branchTokenSink2);
        branch2Raw.append(branch2Intro).append("\n");

        var stepTokenSink3 = Sinks.many().unicast().<String>onBackpressureBuffer();
        sink.tryEmitNext(new SwarmEvent.StepExecutionStarted(branchId2, "compute-trends", null, List.of(), stepTokenSink3.asFlux()));
        Thread.sleep(3000);
        streamTokensToSinks("Computing monthly revenue trends...", branchTokenSink2, stepTokenSink3);
        completeSink(stepTokenSink3);
        Thread.sleep(3000);
        var step3Raw = "Mock step response: compute-trends";
        sink.tryEmitNext(new SwarmEvent.StepExecutionFinished(branchId2, "compute-trends", null, List.of(), mockStepResult("15% YoY revenue growth"), step3Raw));
        branch2Raw.append(step3Raw);

        completeSink(branchTokenSink2);
        sink.tryEmitNext(new SwarmEvent.BranchExecutionFinished(branchId2, mockBranchResult("Steady 15% YoY growth with Q4 spike"), branch2Raw.toString()));
        Thread.sleep(2000);
    }

    private static void emitAnalysis(Sinks.Many<SwarmEvent> sink, String query) throws InterruptedException {
        var id = "analysis";
        var tokenSink = Sinks.many().unicast().<String>onBackpressureBuffer();
        sink.tryEmitNext(new SwarmEvent.AnalysisNegotiationStarted(id, tokenSink.asFlux()));
        Thread.sleep(1500);
        var negotiationRaw = new StringBuilder();

        var intro = "Synthesizing findings for: " + query + "\n\n"
                    + "Main conclusion: healthy growth with strong VIP customer base.\n\n";
        streamTokensToSinks(intro, tokenSink);
        negotiationRaw.append(intro);

        var analysisV1TokenSink = Sinks.many().unicast().<String>onBackpressureBuffer();
        sink.tryEmitNext(new SwarmEvent.AnalysisVersionCreationStarted(id, 1, analysisV1TokenSink.asFlux()));
        streamTokensToSinks("Drafting analysis v1...", tokenSink, analysisV1TokenSink);
        completeSink(analysisV1TokenSink);
        var analysisV1Raw = "Analysis v1 drafted";
        sink.tryEmitNext(new SwarmEvent.AnalysisVersionCreationFinished(id, 1, mockAnalysisV1(), analysisV1Raw));
        negotiationRaw.append(analysisV1Raw).append("\n");

        var analysisV1CritiqueTokenSink = Sinks.many().unicast().<String>onBackpressureBuffer();
        sink.tryEmitNext(new SwarmEvent.AnalysisVersionCritiqueStarted(id, 1, analysisV1CritiqueTokenSink.asFlux()));
        streamTokensToSinks("Critiquing analysis v1 confidence and actionability...", tokenSink, analysisV1CritiqueTokenSink);
        completeSink(analysisV1CritiqueTokenSink);
        var analysisV1CritiqueRaw = "Analysis v1 critique complete";
        sink.tryEmitNext(new SwarmEvent.AnalysisVersionCritiqueFinished(
            id,
            1,
            new com.rorm.ai.swarm.dto.ConclusionCritiqueDTO(
                7.2,
                List.of(
                    new com.rorm.ai.swarm.dto.ConclusionCritiqueDTO.Challenge(
                        "SMB price sensitivity conclusion",
                        com.rorm.ai.swarm.dto.ConclusionCritiqueDTO.ChallengeType.ALTERNATIVE_EXPLANATION,
                        "Claim is too absolute for the available evidence.",
                        "Reframe as leading hypothesis and include uncertainty.",
                        com.rorm.ai.swarm.dto.ConclusionCritiqueDTO.Severity.MEDIUM
                    )
                ),
                List.of("Core growth findings are evidence-backed"),
                "Revision required for confidence framing",
                List.of("Add uncertainty framing", "Prioritize recommendations"),
                com.rorm.ai.swarm.dto.ConclusionCritiqueDTO.RiskLevel.MEDIUM,
                Instant.now()
            ),
            analysisV1CritiqueRaw
        ));
        negotiationRaw.append(analysisV1CritiqueRaw).append("\n");

        var analysisV2TokenSink = Sinks.many().unicast().<String>onBackpressureBuffer();
        sink.tryEmitNext(new SwarmEvent.AnalysisVersionCreationStarted(id, 2, analysisV2TokenSink.asFlux()));
        streamTokensToSinks("Revising analysis to v2 with uncertainty framing...", tokenSink, analysisV2TokenSink);
        completeSink(analysisV2TokenSink);
        var finalAnalysis = mockAnalysisV2();
        var analysisV2Raw = "Analysis v2 drafted";
        sink.tryEmitNext(new SwarmEvent.AnalysisVersionCreationFinished(id, 2, finalAnalysis, analysisV2Raw));
        negotiationRaw.append(analysisV2Raw).append("\n");

        var analysisV2CritiqueTokenSink = Sinks.many().unicast().<String>onBackpressureBuffer();
        sink.tryEmitNext(new SwarmEvent.AnalysisVersionCritiqueStarted(id, 2, analysisV2CritiqueTokenSink.asFlux()));
        streamTokensToSinks("Reviewing analysis v2...", tokenSink, analysisV2CritiqueTokenSink);
        completeSink(analysisV2CritiqueTokenSink);
        var analysisV2CritiqueRaw = "Analysis v2 critique complete";
        sink.tryEmitNext(new SwarmEvent.AnalysisVersionCritiqueFinished(
            id,
            2,
            new com.rorm.ai.swarm.dto.ConclusionCritiqueDTO(
                9.1,
                List.of(),
                List.of("Uncertainty is explicit", "Recommendations are prioritized"),
                "Analysis approved",
                List.of(),
                com.rorm.ai.swarm.dto.ConclusionCritiqueDTO.RiskLevel.LOW,
                Instant.now()
            ),
            analysisV2CritiqueRaw
        ));
        negotiationRaw.append(analysisV2CritiqueRaw);

        Thread.sleep(1500);
        completeSink(tokenSink);
        sink.tryEmitNext(new SwarmEvent.AnalysisNegotiationFinished(id, finalAnalysis, negotiationRaw.toString(), NegotiationFinishReason.APPROVED));
    }

    private static void emitTokens(Sinks.Many<String> tokenSink, String text) throws InterruptedException {
        streamTokensToSinks(text, tokenSink);
        completeSink(tokenSink);
    }

    private static ScoutOverviewDTO mockScout() {
        return new ScoutOverviewDTO(
            List.of(
                new ScoutOverviewDTO.EntitySummary("customers", "Customer records", "10K", List.of("id", "name", "email"), List.of()),
                new ScoutOverviewDTO.EntitySummary("orders", "Purchase orders", "50K", List.of("id", "customer_id", "total"), List.of("orders.customer_id -> customers.id")),
                new ScoutOverviewDTO.EntitySummary("products", "Product catalog", "500", List.of("id", "name", "price"), List.of())
            ),
            List.of(
                new ScoutOverviewDTO.DataQualityIssue(
                    ScoutOverviewDTO.IssueType.MISSING_VALUES,
                    "customers.email",
                    "3% missing emails",
                    ScoutOverviewDTO.Severity.LOW,
                    0.03
                )
            ),
            List.of("Revenue concentration in top 10% customers", "Q4 seasonal spike"),
            List.of("Investigate customer segmentation", "Analyze revenue trends"),
            ScoutOverviewDTO.ComplexityLevel.MEDIUM,
            List.of("Data only available from 2023 onwards"),
            Instant.now()
        );
    }

    @SafeVarargs
    private static void streamTokensToSinks(String text, Sinks.Many<String>... sinks) throws InterruptedException {
        var words = text.split("(?<=\\s)");
        var sinkList = sinks;
        for (var word : words) {
            for (var sink : sinkList) {
                sink.tryEmitNext(word);
            }
            Thread.sleep(150);
        }
    }

    private static void completeSink(Sinks.Many<String> tokenSink) {
        tokenSink.tryEmitComplete();
    }

    private static ResearchPlanDTO mockPlanV1() {
        return new ResearchPlanDTO(
            "Analyze customer and revenue patterns",
            List.of(
                new ResearchPlanDTO.ResearchBranch("customer-segmentation", "Segment and analyze customers",
                    List.of(
                        new ResearchPlanDTO.ResearchStep("segment-customers", "Segment customers by purchase behavior", "RFM analysis", List.of(), List.of()),
                        new ResearchPlanDTO.ResearchStep("analyze-segments", "Analyze segment characteristics", "Compare segment metrics", List.of(), List.of())
                    ), 4, ResearchPlanDTO.Priority.HIGH),
                new ResearchPlanDTO.ResearchBranch("revenue-trends", "Analyze revenue trends",
                    List.of(
                        new ResearchPlanDTO.ResearchStep("compute-trends", "Compute monthly revenue trends", "Time series aggregation", List.of(), List.of())
                    ), 2, ResearchPlanDTO.Priority.MEDIUM)
            ),
            6,
            List.of("Identify key customer segments", "Understand revenue growth drivers"),
            Instant.now()
        );
    }

    private static ResearchPlanDTO mockPlanV2() {
        return new ResearchPlanDTO(
            "Analyze customer and revenue patterns",
            List.of(
                new ResearchPlanDTO.ResearchBranch("customer-segmentation", "Segment and analyze customers",
                    List.of(
                        new ResearchPlanDTO.ResearchStep("segment-customers", "Segment customers by purchase behavior", "RFM analysis", List.of(), List.of()),
                        new ResearchPlanDTO.ResearchStep("analyze-segments", "Analyze segment characteristics", "Compare segment metrics", List.of(), List.of())
                    ), 4, ResearchPlanDTO.Priority.HIGH),
                new ResearchPlanDTO.ResearchBranch("revenue-trends", "Analyze revenue trends",
                    List.of(
                        new ResearchPlanDTO.ResearchStep("compute-trends", "Compute monthly revenue trends", "Time series aggregation", List.of(), List.of()),
                        new ResearchPlanDTO.ResearchStep("validate-trends", "Validate trend stability", "Recompute trends by quarter and compare variance", List.of(new StepRef("customer-segmentation", "segment-customers")), List.of())
                    ), 3, ResearchPlanDTO.Priority.MEDIUM)
            ),
            7,
            List.of("Identify key customer segments", "Understand revenue growth drivers"),
            Instant.now()
        );
    }

    private static StepExecutionResultDTO mockStepResult(String keyInsight) {
        return new StepExecutionResultDTO(
            "Mock step completed successfully",
            keyInsight,
            "Detailed mock findings",
            List.of(new StepExecutionResultDTO.ResearchActionDTO("Analyzed data", "Ran query", "Found results")),
            Map.of(),
            Instant.now()
        );
    }

    private static BranchExecutionResultDTO mockBranchResult(String summary) {
        return new BranchExecutionResultDTO(
            "Mock branch goal",
            summary,
            List.of("Mock insight"),
            List.of(),
            Map.of(),
            Instant.now()
        );
    }

    private static AnalysisResultDTO mockAnalysisV1() {
        return new AnalysisResultDTO(
            "Business shows healthy growth with strong VIP customer base.",
            List.of(),
            8.0,
            List.of("Growth could be partially seasonality-driven"),
            List.of(),
            false,
            List.of(),
            List.of("Assumes order data represents all revenue channels"),
            Instant.now()
        );
    }

    private static AnalysisResultDTO mockAnalysisV2() {
        return new AnalysisResultDTO(
            "Business shows healthy growth with strong VIP customer base. Price sensitivity is the leading churn hypothesis for SMB customers, with seasonality as a secondary explanation. Prioritized actions: run SMB pricing experiments, deploy retention playbook, and diversify product exposure.",
            List.of(),
            9.0,
            List.of("Growth could be partially seasonality-driven"),
            List.of(),
            false,
            List.of(),
            List.of("Assumes order data represents all revenue channels"),
            Instant.now()
        );
    }

}
