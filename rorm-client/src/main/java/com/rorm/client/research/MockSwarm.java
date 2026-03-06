package com.rorm.client.research;

import com.rorm.ai.swarm.NegotiationFinishReason;
import com.rorm.ai.swarm.SwarmEvent;
import com.rorm.ai.swarm.dto.*;
import com.rorm.ai.swarm.dto.PlanCritiqueDTO.PlanChallenge;
import com.rorm.ai.swarm.dto.PlanCritiqueDTO.PlanModification;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Instant;
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
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                eventSink.tryEmitError(e);
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
                    + "Hypothesis 1: VIP customers drive disproportionate revenue\n"
                    + "Hypothesis 2: Revenue growth is organic, not seasonality-driven\n\n"
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
        streamTokensToSinks("Reviewing plan v1 for research design and dependencies...", tokenSink, planV1CritiqueTokenSink);
        completeSink(planV1CritiqueTokenSink);
        var planV1CritiqueRaw = "Plan v1 critique complete";
        sink.tryEmitNext(new SwarmEvent.PlanVersionCritiqueFinished(
            id,
            1,
            new PlanCritiqueDTO(
                List.of(
                    new PlanChallenge(
                        "branch:customer-segmentation:step:analyze-segments",
                        PlanCritiqueDTO.PlanChallengeType.WRONG_DEPENDENCY,
                        "Analyze step does not explicitly depend on segmentation outputs.",
                        "Declare dependency on segment-customers output.",
                        PlanCritiqueDTO.Severity.HIGH
                    ),
                    new PlanChallenge(
                        "branch:customer-segmentation",
                        PlanCritiqueDTO.PlanChallengeType.CONFOUNDED_ANALYSIS,
                        "Segmentation branch does not control for order recency — inactive customers with historically high spend would be classified as VIP despite being churned.",
                        "Add recency filter or include last_order_date as a segmentation dimension to separate active VIPs from lapsed ones.",
                        PlanCritiqueDTO.Severity.HIGH
                    ),
                    new PlanChallenge(
                        "branch:revenue-trends",
                        PlanCritiqueDTO.PlanChallengeType.MISSING_CONTROL_GROUP,
                        "Revenue trend branch lacks a baseline comparison — no way to distinguish organic growth from seasonality without comparing against prior year periods.",
                        "Add a step comparing YoY same-period revenue to isolate growth from seasonal patterns.",
                        PlanCritiqueDTO.Severity.MEDIUM
                    )
                ),
                List.of("Clear hypothesis-driven branch goals", "Good use of falsifiable null conditions"),
                5.8,
                "Research design has gaps: uncontrolled confound in segmentation (recency) and missing baseline in trend analysis. Structural dependency issue also needs fixing. Revision required.",
                List.of(
                    new PlanModification(
                        PlanCritiqueDTO.ModificationType.REORDER_DEPENDENCIES,
                        "customer-segmentation/analyze-segments",
                        "Use outputs from segment-customers as required input."
                    ),
                    new PlanModification(
                        PlanCritiqueDTO.ModificationType.ADD_CONFOUND_CONTROL,
                        "customer-segmentation",
                        "Add recency dimension to segmentation to avoid conflating active and lapsed high-value customers."
                    ),
                    new PlanModification(
                        PlanCritiqueDTO.ModificationType.ADD_CONTROL_GROUP,
                        "revenue-trends",
                        "Add YoY comparison step to separate organic growth from seasonal effects."
                    )
                ),
                PlanCritiqueDTO.RiskLevel.MEDIUM,
                Instant.now()
            ),
            planV1CritiqueRaw
        ));
        negotiationRaw.append(planV1CritiqueRaw).append("\n");

        var planV2TokenSink = Sinks.many().unicast().<String>onBackpressureBuffer();
        sink.tryEmitNext(new SwarmEvent.PlanVersionCreationStarted(id, 2, planV2TokenSink.asFlux()));
        streamTokensToSinks("Updating plan to v2 with confound controls and baseline comparison...", tokenSink, planV2TokenSink);
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
            new PlanCritiqueDTO(
                List.of(),
                List.of(
                    "Hypotheses are specific and falsifiable",
                    "Confounds explicitly identified and controlled in step design",
                    "Dependencies are explicit and traceable",
                    "YoY baseline comparison isolates seasonality from growth"
                ),
                8.5,
                "Plan approved. Research design is sound: hypotheses are falsifiable, major confounds addressed, and baseline comparisons included. Minor improvement possible by adding tenure control, but not blocking.",
                List.of(),
                PlanCritiqueDTO.RiskLevel.LOW,
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
        streamTokensToSinks("Segmenting customers by purchase frequency, value, and recency...", branchTokenSink1, stepTokenSink1);
        completeSink(stepTokenSink1);
        Thread.sleep(3000);
        var step1Raw = "Mock step response: segment-customers";
        sink.tryEmitNext(new SwarmEvent.StepExecutionFinished(branchId1, "segment-customers", null, List.of(), mockStepResult("Segmented customers into 4 groups using RFM, controlling for recency"), step1Raw));
        branch1Raw.append(step1Raw).append("\n");

        var stepTokenSink2 = Sinks.many().unicast().<String>onBackpressureBuffer();
        sink.tryEmitNext(new SwarmEvent.StepExecutionStarted(branchId1, "analyze-segments", "segment-customers", List.of(new StepRef(branchId1, "segment-customers")), stepTokenSink2.asFlux()));
        Thread.sleep(3000);
        streamTokensToSinks("Analyzing segment characteristics and testing VIP concentration hypothesis...", branchTokenSink1, stepTokenSink2);
        completeSink(stepTokenSink2);
        Thread.sleep(3000);
        var step2Raw = "Mock step response: analyze-segments";
        sink.tryEmitNext(new SwarmEvent.StepExecutionFinished(branchId1, "analyze-segments", "segment-customers", List.of(new StepRef(branchId1, "segment-customers")), mockStepResult("Active VIP segment (8% of customers) drives 62% of revenue — concentration holds after excluding lapsed VIPs"), step2Raw));
        branch1Raw.append(step2Raw);

        completeSink(branchTokenSink1);
        sink.tryEmitNext(new SwarmEvent.BranchExecutionFinished(branchId1, mockBranchResult("Hypothesis SUPPORTED: active VIP concentration confirmed at 62% revenue from 8% of customers, robust after recency control"), branch1Raw.toString()));
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
        sink.tryEmitNext(new SwarmEvent.StepExecutionFinished(branchId2, "compute-trends", null, List.of(), mockStepResult("15% YoY revenue growth with Q4 spike of 28%"), step3Raw));
        branch2Raw.append(step3Raw).append("\n");

        var stepTokenSink4 = Sinks.many().unicast().<String>onBackpressureBuffer();
        sink.tryEmitNext(new SwarmEvent.StepExecutionStarted(branchId2, "validate-trends", "compute-trends", List.of(new StepRef("customer-segmentation", "segment-customers")), stepTokenSink4.asFlux()));
        Thread.sleep(3000);
        streamTokensToSinks("Validating trend stability with YoY same-period comparison...", branchTokenSink2, stepTokenSink4);
        completeSink(stepTokenSink4);
        Thread.sleep(3000);
        var step4Raw = "Mock step response: validate-trends";
        sink.tryEmitNext(new SwarmEvent.StepExecutionFinished(branchId2, "validate-trends", "compute-trends", List.of(new StepRef("customer-segmentation", "segment-customers")), mockStepResult("YoY comparison shows 10% organic growth after removing seasonal Q4 effect — hypothesis partially supported"), step4Raw));
        branch2Raw.append(step4Raw);

        completeSink(branchTokenSink2);
        sink.tryEmitNext(new SwarmEvent.BranchExecutionFinished(branchId2, mockBranchResult("Hypothesis PARTIALLY SUPPORTED: 10% organic growth confirmed, but Q4 spike accounts for ~5pp of apparent 15% total growth"), branch2Raw.toString()));
        Thread.sleep(2000);
    }

    private static void emitAnalysis(Sinks.Many<SwarmEvent> sink, String query) throws InterruptedException {
        var id = "analysis";
        var tokenSink = Sinks.many().unicast().<String>onBackpressureBuffer();
        sink.tryEmitNext(new SwarmEvent.AnalysisNegotiationStarted(id, tokenSink.asFlux()));
        Thread.sleep(1500);
        var negotiationRaw = new StringBuilder();

        var intro = "Synthesizing findings for: " + query + "\n\n"
                    + "Evaluating hypotheses against branch evidence...\n"
                    + "Main conclusion: healthy organic growth with strong VIP customer base.\n\n";
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
            new ConclusionCritiqueDTO(
                7.2,
                List.of(
                    new ConclusionCritiqueDTO.Challenge(
                        "SMB price sensitivity conclusion",
                        ConclusionCritiqueDTO.ChallengeType.ALTERNATIVE_EXPLANATION,
                        "Claim is too absolute for the available evidence. Seasonality was identified as a confound but not fully disentangled from price sensitivity.",
                        "Reframe as leading hypothesis and include uncertainty. Note that the Q4 seasonal effect accounts for ~5pp of apparent growth.",
                        ConclusionCritiqueDTO.Severity.MEDIUM
                    )
                ),
                List.of("Core growth findings are evidence-backed with confound controls"),
                "Revision required for confidence framing",
                List.of("Add uncertainty framing for confounded claims", "Prioritize recommendations by evidence strength"),
                ConclusionCritiqueDTO.RiskLevel.MEDIUM,
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
            new ConclusionCritiqueDTO(
                9.1,
                List.of(),
                List.of("Hypothesis evaluations are explicit and honest", "Uncertainty is properly calibrated to confound control", "Recommendations are prioritized by evidence strength"),
                "Analysis approved",
                List.of(),
                ConclusionCritiqueDTO.RiskLevel.LOW,
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
        for (var word : words) {
            for (var sink : sinks) {
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
            "Analyze customer and revenue patterns to identify growth drivers and concentration risks",
            List.of(
                "A small number of VIP customers drive disproportionate revenue (>50% from <10%)",
                "Revenue growth is primarily organic, not driven by seasonal effects"
            ),
            List.of(
                new ResearchPlanDTO.ResearchBranch(
                    "customer-segmentation",
                    "Segment customers and test whether VIP concentration creates revenue risk",
                    "Active VIP customers (top 10% by spend) account for >50% of total revenue, creating concentration risk",
                    "If revenue is distributed relatively evenly across customer tiers (no single tier >30%)",
                    List.of("Order recency — lapsed high-spenders inflate VIP counts without contributing current revenue"),
                    List.of(
                        new ResearchPlanDTO.ResearchStep(
                            "segment-customers",
                            "Segment customers by purchase behavior using RFM dimensions",
                            "Use recency, frequency, and monetary value to create customer tiers. Include recency as a dimension to separate active VIPs from lapsed high-spenders — this controls for the recency confound identified by Scout.",
                            List.of(),
                            List.of()
                        ),
                        new ResearchPlanDTO.ResearchStep(
                            "analyze-segments",
                            "Test whether active VIP segment drives disproportionate revenue",
                            "Calculate revenue share per segment from step 1. If active VIPs (high recency + high value) contribute >50% of revenue, the concentration hypothesis is supported. Check whether the pattern holds when excluding the most recent quarter to rule out recency bias.",
                            List.of(),
                            List.of()
                        )
                    ),
                    4,
                    ResearchPlanDTO.Priority.HIGH
                ),
                new ResearchPlanDTO.ResearchBranch(
                    "revenue-trends",
                    "Analyze revenue trends and distinguish organic growth from seasonality",
                    "Observed 15% YoY revenue growth is primarily organic, with seasonality contributing <5 percentage points",
                    "If YoY same-period comparisons show flat or declining revenue when Q4 is excluded",
                    List.of("Seasonality — Q4 holiday spike could inflate apparent growth rate"),
                    List.of(
                        new ResearchPlanDTO.ResearchStep(
                            "compute-trends",
                            "Compute monthly revenue trends and identify seasonal patterns",
                            "Calculate monthly revenue aggregates and identify seasonal peaks. Compare Q4 revenue to other quarters to quantify the seasonal component.",
                            List.of(),
                            List.of()
                        )
                    ),
                    2,
                    ResearchPlanDTO.Priority.MEDIUM
                )
            ),
            6,
            List.of(
                "Can we confirm whether VIP concentration exists after controlling for customer recency?",
                "What portion of revenue growth is organic vs seasonal?"
            ),
            Instant.now()
        );
    }

    private static ResearchPlanDTO mockPlanV2() {
        return new ResearchPlanDTO(
            "Analyze customer and revenue patterns to identify growth drivers and concentration risks",
            List.of(
                "A small number of VIP customers drive disproportionate revenue (>50% from <10%)",
                "Revenue growth is primarily organic, not driven by seasonal effects"
            ),
            List.of(
                new ResearchPlanDTO.ResearchBranch(
                    "customer-segmentation",
                    "Segment customers and test whether VIP concentration creates revenue risk",
                    "Active VIP customers (top 10% by spend) account for >50% of total revenue, creating concentration risk",
                    "If revenue is distributed relatively evenly across customer tiers (no single tier >30%)",
                    List.of(
                        "Order recency — lapsed high-spenders inflate VIP counts without contributing current revenue",
                        "Product mix — VIPs might cluster in one product line, making concentration a product risk not a customer risk"
                    ),
                    List.of(
                        new ResearchPlanDTO.ResearchStep(
                            "segment-customers",
                            "Segment customers by purchase behavior using RFM dimensions, controlling for recency",
                            "Use recency, frequency, and monetary value to create customer tiers. Include recency as a primary dimension to separate active VIPs from lapsed high-spenders. This addresses the recency confound: without it, churned customers with historical high spend would be misclassified as VIPs.",
                            List.of(),
                            List.of()
                        ),
                        new ResearchPlanDTO.ResearchStep(
                            "analyze-segments",
                            "Test whether active VIP segment drives disproportionate revenue independently of product mix",
                            "Calculate revenue share per segment from step 1. If active VIPs contribute >50% of revenue, test whether this holds across product categories to rule out product-mix confound. If concentration only appears in one product line, it's a product risk not a customer risk.",
                            List.of(new StepRef("customer-segmentation", "segment-customers")),
                            List.of()
                        )
                    ),
                    4,
                    ResearchPlanDTO.Priority.HIGH
                ),
                new ResearchPlanDTO.ResearchBranch(
                    "revenue-trends",
                    "Analyze revenue trends and distinguish organic growth from seasonality",
                    "Observed 15% YoY revenue growth is primarily organic, with seasonality contributing <5 percentage points",
                    "If YoY same-period comparisons show flat or declining revenue when Q4 is excluded",
                    List.of(
                        "Seasonality — Q4 holiday spike could inflate apparent growth rate",
                        "Customer mix shift — growth might come from acquiring more low-value customers rather than genuine per-customer growth"
                    ),
                    List.of(
                        new ResearchPlanDTO.ResearchStep(
                            "compute-trends",
                            "Compute monthly revenue trends and quantify seasonal component",
                            "Calculate monthly revenue aggregates and compare Q4 to non-Q4 periods. Quantify the seasonal component by comparing same-period YoY growth rates. If Q4 growth is 3x+ other quarters, seasonality is significant.",
                            List.of(),
                            List.of()
                        ),
                        new ResearchPlanDTO.ResearchStep(
                            "validate-trends",
                            "Validate that growth is organic by comparing YoY same-period revenue and controlling for customer count changes",
                            "Compare revenue YoY excluding Q4 to isolate organic growth. Also check whether growth comes from more customers (volume) or higher spend per customer (intensity). If per-customer revenue is flat while customer count grows, the growth mechanism is acquisition not retention — different strategic implication.",
                            List.of(new StepRef("customer-segmentation", "segment-customers")),
                            List.of()
                        )
                    ),
                    3,
                    ResearchPlanDTO.Priority.MEDIUM
                )
            ),
            7,
            List.of(
                "Can we confirm whether VIP concentration exists after controlling for customer recency and product mix?",
                "What portion of revenue growth is organic vs seasonal, and is it driven by acquisition or retention?"
            ),
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
            "Business shows healthy growth with strong VIP customer base. VIP concentration hypothesis SUPPORTED: 8% of active customers drive 62% of revenue, robust after recency control. Growth hypothesis PARTIALLY SUPPORTED: 10% organic growth confirmed, but Q4 seasonality accounts for ~5pp of the apparent 15% total.",
            List.of(),
            8.0,
            List.of("Growth could be partially driven by customer acquisition rather than per-customer spending increase"),
            List.of(),
            false,
            List.of(),
            List.of("Assumes order data represents all revenue channels"),
            Instant.now()
        );
    }

    private static AnalysisResultDTO mockAnalysisV2() {
        return new AnalysisResultDTO(
            "Business shows healthy growth with strong VIP customer base. VIP concentration hypothesis SUPPORTED (high confidence): 8% of active customers drive 62% of revenue, confirmed after controlling for recency and product mix. Growth hypothesis PARTIALLY SUPPORTED (moderate confidence): 10% organic growth confirmed, but seasonality contributes ~5pp and acquisition vs retention split remains uncertain. Price sensitivity is the leading churn hypothesis for SMB customers, with seasonality as an alternative explanation requiring further investigation.",
            List.of(),
            8.5,
            List.of("Growth mechanism (acquisition vs retention) not fully disentangled — requires cohort analysis"),
            List.of(),
            true,
            List.of("Cohort analysis to separate acquisition-driven from retention-driven growth"),
            List.of("Assumes order data represents all revenue channels", "Recency control uses 12-month window which may exclude valid seasonal buyers"),
            Instant.now()
        );
    }

}