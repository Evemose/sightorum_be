package com.rorm.client.research;

import com.rorm.ai.swarm.NegotiationFinishReason;
import com.rorm.ai.swarm.SwarmEvent;
import com.rorm.ai.swarm.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Mock service for testing swarm SSE output.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SwarmMockService {

    private final SwarmEventPublisher swarmEventPublisher;

    /**
     * Streams mock swarm events to simulate a complete research flow with realistic delays.
     */
    public void streamMockSwarmEvents(UUID sessionId) {
        log.info("Starting mock swarm event stream for session {}", sessionId);

        createMockEventStream(sessionId)
            .doOnNext(event -> {
                log.debug("Publishing mock event: {}", event.getClass().getSimpleName());
                swarmEventPublisher.publish(sessionId, event);
            })
            .doOnComplete(() -> log.info("Mock swarm stream completed for session {}", sessionId))
            .doOnError(e -> log.error("Error in mock swarm stream: {}", e.getMessage()))
            .subscribe();
    }

    private Flux<SwarmEvent> createMockEventStream(UUID sessionId) {
        return MockSwarm.research("Mock research session " + sessionId);
    }

    private Flux<SwarmEvent> createMockEventStream() {
        var scoutId = "scout";
        var planningId = "plan";
        var branchId1 = "branch-customer-analysis";
        var branchId2 = "branch-product-trends";
        var analysisId = "analysis";

        // Scout phase - 2 seconds
        var scoutPhase = Flux.just(
            new SwarmEvent.ScoutStarted(scoutId, Flux.just("Analyzing", " data", " landscape...")),
            new SwarmEvent.ScoutFinished(
                scoutId,
                new ScoutOverviewDTO(
                    List.of(),
                    List.of(),
                    List.of(
                        "Revenue has increased 23% in Q4 2024",
                        "Enterprise segment shows highest engagement"
                    ),
                    List.of(
                        "Investigate customer churn patterns",
                        "Analyze product purchase correlations"
                    ),
                    null,
                    List.of("No data before 2023", "Customer emails may contain typos"),
                    Instant.now()
                ),
                "Scout analysis complete. Found 2 entities, 1 quality issue, medium complexity."
            )
        ).delayElements(Duration.ofSeconds(2));

        // Planning phase - negotiation with revision cycle (v1 -> critique -> v2 -> approved)
        var planningPhase = Flux.just(
            new SwarmEvent.PlanNegotiationStarted(planningId, Flux.just("Creating", " research", " plan...")),
            new SwarmEvent.PlanVersionCreationStarted(planningId, 1, Flux.just("Drafting", " plan", " v1...")),
            new SwarmEvent.PlanVersionCreationFinished(
                planningId,
                1,
                createMockPlanV1(branchId1, branchId2),
                "Plan v1 created with 2 branches but weak dependency coverage"
            ),
            new SwarmEvent.PlanVersionCritiqueStarted(planningId, 1, Flux.just("Reviewing", " plan...")),
            new SwarmEvent.PlanVersionCritiqueFinished(
                planningId,
                1,
                new PlanCritiqueDTO(
                    List.of(
                        new PlanCritiqueDTO.PlanChallenge(
                            "branch-customer-analysis/step_2",
                            PlanCritiqueDTO.PlanChallengeType.WRONG_DEPENDENCY,
                            "Step_2 does not depend on step_1 outputs, so churn segmentation may be inconsistent.",
                            "Declare dependency from step_2 to step_1 and consume segmentation outputs.",
                            PlanCritiqueDTO.Severity.HIGH
                        ),
                        new PlanCritiqueDTO.PlanChallenge(
                            "branch-product-trends",
                            PlanCritiqueDTO.PlanChallengeType.INCOMPLETE_DECOMPOSITION,
                            "Product concentration metric is computed once with no robustness check.",
                            "Add a validation step to verify top10_revenue_share stability across cohorts.",
                            PlanCritiqueDTO.Severity.MEDIUM
                        )
                    ),
                    List.of("Branch goals are clear"),
                    6.9,
                    "Plan requires revision before approval",
                    List.of(
                        new PlanCritiqueDTO.PlanModification(
                            PlanCritiqueDTO.ModificationType.REORDER_DEPENDENCIES,
                            "branch-customer-analysis/step_2",
                            "Wire step_2 to step_1 outputs before churn calculations."
                        ),
                        new PlanCritiqueDTO.PlanModification(
                            PlanCritiqueDTO.ModificationType.MODIFY_STEP,
                            "branch-product-trends",
                            "Add metric validation step for top10_revenue_share."
                        )
                    ),
                    PlanCritiqueDTO.RiskLevel.MEDIUM,
                    Instant.now()
                ),
                "Plan v1 critique: revisions requested"
            ),
            new SwarmEvent.PlanVersionCreationStarted(planningId, 2, Flux.just("Revising", " plan", " v2...")),
            new SwarmEvent.PlanVersionCreationFinished(
                planningId,
                2,
                createMockPlanV2(branchId1, branchId2),
                "Plan v2 updated with explicit dependencies and validation step"
            ),
            new SwarmEvent.PlanVersionCritiqueStarted(planningId, 2, Flux.just("Re-reviewing", " plan...")),
            new SwarmEvent.PlanVersionCritiqueFinished(
                planningId,
                2,
                new PlanCritiqueDTO(
                    List.of(),
                    List.of(
                        "Dependencies now explicit across customer steps",
                        "Validation step added for concentration metric",
                        "Branch sequencing supports synthesis quality"
                    ),
                    9.1,
                    "Plan approved after revision",
                    List.of(),
                    PlanCritiqueDTO.RiskLevel.LOW,
                    Instant.now()
                ),
                "Plan v2 critique: approved"
            ),
            new SwarmEvent.PlanNegotiationFinished(
                planningId,
                createMockPlanV2(branchId1, branchId2),
                "Final plan accepted",
                NegotiationFinishReason.APPROVED
            )
        ).delayElements(Duration.ofSeconds(1));

        // Branch 1 execution - customer analysis (2 steps: 3 + 2.5 seconds)
        var branch1Step1 = Flux.just(
            new SwarmEvent.BranchExecutionStarted(branchId1, Flux.just("Executing", " customer", " analysis...")),
            new SwarmEvent.StepExecutionStarted(branchId1, "step_1", null, List.of(), Flux.just("Segmenting", " customers...")),
            new SwarmEvent.StepExecutionFinished(
                branchId1,
                "step_1",
                null,
                List.of(),
                new StepExecutionResultDTO(
                    "Segmented customers into 3 tiers: Enterprise (12%), SMB (45%), Individual (43%)",
                    "Enterprise customers have 3.2x higher lifetime value",
                    "Analysis revealed clear segmentation with distinct behavioral patterns",
                    List.of(),
                    Map.of(
                        "enterprise_count", 6000,
                        "smb_count", 22500,
                        "avg_enterprise_ltv", 12450.0
                    ),
                    Instant.now()
                ),
                "Step 1 complete"
            )
        ).delayElements(Duration.ofMillis(1500));

        var branch1Step2 = Flux.just(
            new SwarmEvent.StepExecutionStarted(branchId1, "step_2", "step_1", List.of(new StepRef(branchId1, "step_1")), Flux.just("Analyzing", " churn", " rates...")),
            new SwarmEvent.StepExecutionFinished(
                branchId1,
                "step_2",
                "step_1",
                List.of(new StepRef(branchId1, "step_1")),
                new StepExecutionResultDTO(
                    "Churn rate: Enterprise 8%, SMB 34%, Individual 42%",
                    "SMB segment shows concerning churn, driven by price sensitivity",
                    "Churn analysis reveals retention challenge in SMB segment",
                    List.of(),
                    Map.of(
                        "enterprise_churn_rate", 0.08,
                        "smb_churn_rate", 0.34,
                        "individual_churn_rate", 0.42
                    ),
                    Instant.now()
                ),
                "Step 2 complete"
            )
        ).delayElements(Duration.ofMillis(1250));

        var branch1Finish = Flux.just(
            new SwarmEvent.BranchExecutionFinished(
                branchId1,
                new BranchExecutionResultDTO(
                    "Analyze customer segmentation and churn patterns",
                    "Customer analysis revealed strong enterprise retention (92%) but high SMB churn (34%). " +
                    "Enterprise segment drives 58% of revenue despite being only 12% of customer base. " +
                    "Price sensitivity is primary SMB churn driver.",
                    List.of(
                        "Enterprise segment highly profitable with low churn",
                        "SMB segment at risk with 34% annual churn rate",
                        "Price sensitivity drives SMB churn"
                    ),
                    List.of(
                        new StepExecutionResultDTO(
                            "Segmented customers into 3 tiers",
                            "Enterprise customers have 3.2x higher lifetime value",
                            "Clear segmentation found",
                            List.of(),
                            Map.of("enterprise_count", 6000),
                            Instant.now()
                        ),
                        new StepExecutionResultDTO(
                            "Calculated churn rates by segment",
                            "SMB segment shows concerning churn",
                            "Churn analysis complete",
                            List.of(),
                            Map.of("smb_churn_rate", 0.34),
                            Instant.now()
                        )
                    ),
                    Map.of(
                        "enterprise_count", "6000",
                        "smb_churn_rate", "0.34",
                        "avg_enterprise_ltv", "12450.0"
                    ),
                    Instant.now()
                ),
                "Branch customer analysis complete"
            )
        ).delaySequence(Duration.ofMillis(500));

        // Branch 2 execution - product trends (1 step: 2 seconds) - runs in parallel with branch 1
        var branch2Step1 = Flux.just(
            new SwarmEvent.BranchExecutionStarted(branchId2, Flux.just("Analyzing", " product", " trends...")),
            new SwarmEvent.StepExecutionStarted(branchId2, "step_1", null, List.of(), Flux.just("Identifying", " top", " products...")),
            new SwarmEvent.StepExecutionFinished(
                branchId2,
                "step_1",
                null,
                List.of(),
                new StepExecutionResultDTO(
                    "Top 10 products account for 67% of revenue",
                    "Product concentration is very high",
                    "Revenue highly concentrated in top products",
                    List.of(),
                    Map.of("top10_revenue_share", "0.67"),
                    Instant.now()
                ),
                "Step 1 complete"
            )
        ).delayElements(Duration.ofMillis(1000));

        var branch2Finish = Flux.just(
            new SwarmEvent.BranchExecutionFinished(
                branchId2,
                new BranchExecutionResultDTO(
                    "Analyze product purchase trends",
                    "Product analysis shows high revenue concentration. Top 10 products drive 67% of revenue. " +
                    "Product diversification opportunity identified.",
                    List.of(
                        "High product concentration presents risk",
                        "Opportunity for product line expansion"
                    ),
                    List.of(
                        new StepExecutionResultDTO(
                            "Top 10 products account for 67% of revenue",
                            "Product concentration is very high",
                            "Revenue analysis complete",
                            List.of(),
                            Map.of("top10_revenue_share", "0.67"),
                            Instant.now()
                        )
                    ),
                    Map.of("top10_revenue_share", "0.67"),
                    Instant.now()
                ),
                "Branch product trends complete"
            )
        ).delaySequence(Duration.ofMillis(500));

        // Analysis phase - negotiation with revision cycle (v1 -> critique -> v2 -> approved)
        var analysisPhase = Flux.just(
            new SwarmEvent.AnalysisNegotiationStarted(analysisId, Flux.just("Synthesizing", " findings...")),
            new SwarmEvent.AnalysisVersionCreationStarted(analysisId, 1, Flux.just("Creating", " analysis...")),
            new SwarmEvent.AnalysisVersionCreationFinished(
                analysisId,
                1,
                createMockAnalysisV1(),
                "Analysis v1 created with preliminary conclusions"
            ),
            new SwarmEvent.AnalysisVersionCritiqueStarted(analysisId, 1, Flux.just("Reviewing", " conclusions...")),
            new SwarmEvent.AnalysisVersionCritiqueFinished(
                analysisId,
                1,
                new ConclusionCritiqueDTO(
                    7.1,
                    List.of(
                        new ConclusionCritiqueDTO.Challenge(
                            "SMB churn primary driver is price sensitivity",
                            ConclusionCritiqueDTO.ChallengeType.ALTERNATIVE_EXPLANATION,
                            "Conclusion over-weights price as a sole cause without adequate uncertainty framing.",
                            "Frame price sensitivity as leading hypothesis and include seasonality as alternative.",
                            ConclusionCritiqueDTO.Severity.MEDIUM
                        ),
                        new ConclusionCritiqueDTO.Challenge(
                            "Concentration risk mitigation recommendation",
                            ConclusionCritiqueDTO.ChallengeType.INSUFFICIENT_EVIDENCE,
                            "Recommendation lacks execution ordering and rationale tied to evidence strength.",
                            "Provide prioritized mitigation steps with rationale and expected impact.",
                            ConclusionCritiqueDTO.Severity.MEDIUM
                        )
                    ),
                    List.of("Core findings are data-backed"),
                    "Analysis needs tighter uncertainty framing and prioritized actions",
                    List.of(
                        "Qualify churn driver claim as leading hypothesis",
                        "Prioritize mitigation steps with sequence and rationale"
                    ),
                    ConclusionCritiqueDTO.RiskLevel.MEDIUM,
                    Instant.now()
                ),
                "Analysis v1 critique: revisions requested"
            ),
            new SwarmEvent.AnalysisVersionCreationStarted(analysisId, 2, Flux.just("Revising", " analysis...")),
            new SwarmEvent.AnalysisVersionCreationFinished(
                analysisId,
                2,
                createMockAnalysisV2(),
                "Analysis v2 created with uncertainty framing and action ordering"
            ),
            new SwarmEvent.AnalysisVersionCritiqueStarted(analysisId, 2, Flux.just("Re-reviewing", " conclusions...")),
            new SwarmEvent.AnalysisVersionCritiqueFinished(
                analysisId,
                2,
                new ConclusionCritiqueDTO(
                    9.2,
                    List.of(),
                    List.of(
                        "Uncertainty and assumptions are explicit",
                        "Recommendations are prioritized and operationally clear"
                    ),
                    "Analysis approved after revision",
                    List.of(),
                    ConclusionCritiqueDTO.RiskLevel.LOW,
                    Instant.now()
                ),
                "Analysis v2 critique: approved"
            ),
            new SwarmEvent.AnalysisNegotiationFinished(
                analysisId,
                createMockAnalysisV2(),
                "Final analysis complete",
                NegotiationFinishReason.APPROVED
            )
        ).delayElements(Duration.ofMillis(750));

        // Combine all phases sequentially, with branch executions in parallel
        return Flux.concat(
            scoutPhase,
            planningPhase,
            Flux.merge(
                // Branch 1: starts immediately, takes ~7 seconds total
                Flux.concat(branch1Step1, branch1Step2, branch1Finish),
                // Branch 2: starts immediately (parallel), takes ~3.5 seconds total
                Flux.concat(branch2Step1, branch2Finish)
            ),
            analysisPhase
        );
    }

    private ResearchPlanDTO createMockPlanV1(String branchId1, String branchId2) {
        return new ResearchPlanDTO(
            "Analyze customer behavior and product trends to identify growth opportunities",
            List.of(
                new ResearchPlanDTO.ResearchBranch(
                    branchId1,
                    "Analyze customer segmentation and churn patterns",
                    List.of(
                        new ResearchPlanDTO.ResearchStep(
                            "step_1",
                            "Segment customers by revenue tier",
                            "Use SQL aggregation on orders table grouped by customer",
                            List.of(),
                            List.of(
                                new ResearchVariable("enterprise_count", "Number of enterprise customers", ResearchVariable.VariableType.NUMBER, new StepRef(branchId1, "step_1")),
                                new ResearchVariable("avg_enterprise_ltv", "Average enterprise customer lifetime value", ResearchVariable.VariableType.NUMBER, new StepRef(branchId1, "step_1"))
                            )
                        ),
                        new ResearchPlanDTO.ResearchStep(
                            "step_2",
                            "Calculate churn rate by segment",
                            "Compare active customers year-over-year",
                            List.of(),
                            List.of(
                                new ResearchVariable("smb_churn_rate", "SMB segment annual churn rate", ResearchVariable.VariableType.NUMBER, new StepRef(branchId1, "step_2"))
                            )
                        )
                    ),
                    7,
                    ResearchPlanDTO.Priority.HIGH
                ),
                new ResearchPlanDTO.ResearchBranch(
                    branchId2,
                    "Analyze product purchase trends",
                    List.of(
                        new ResearchPlanDTO.ResearchStep(
                            "step_1",
                            "Identify top-selling products",
                            "Aggregate order line items by product",
                            List.of(),
                            List.of(
                                new ResearchVariable("top10_revenue_share", "Revenue share of top 10 products", ResearchVariable.VariableType.NUMBER, new StepRef(branchId2, "step_1"))
                            )
                        )
                    ),
                    4,
                    ResearchPlanDTO.Priority.MEDIUM
                )
            ),
            11,
            List.of(
                "Identify customer segments with highest growth potential",
                "Understand churn drivers",
                "Identify product opportunities"
            ),
            Instant.now()
        );
    }

    private ResearchPlanDTO createMockPlanV2(String branchId1, String branchId2) {
        return new ResearchPlanDTO(
            "Analyze customer behavior and product trends to identify growth opportunities",
            List.of(
                new ResearchPlanDTO.ResearchBranch(
                    branchId1,
                    "Analyze customer segmentation and churn patterns",
                    List.of(
                        new ResearchPlanDTO.ResearchStep(
                            "step_1",
                            "Segment customers by revenue tier",
                            "Use SQL aggregation on orders table grouped by customer",
                            List.of(),
                            List.of(
                                new ResearchVariable("enterprise_count", "Number of enterprise customers", ResearchVariable.VariableType.NUMBER, new StepRef(branchId1, "step_1")),
                                new ResearchVariable("avg_enterprise_ltv", "Average enterprise customer lifetime value", ResearchVariable.VariableType.NUMBER, new StepRef(branchId1, "step_1"))
                            )
                        ),
                        new ResearchPlanDTO.ResearchStep(
                            "step_2",
                            "Calculate churn rate by segment",
                            "Compare active customers year-over-year using segment definitions from step_1",
                            List.of(new StepRef(branchId1, "step_1")),
                            List.of(
                                new ResearchVariable("smb_churn_rate", "SMB segment annual churn rate", ResearchVariable.VariableType.NUMBER, new StepRef(branchId1, "step_2"))
                            )
                        )
                    ),
                    7,
                    ResearchPlanDTO.Priority.HIGH
                ),
                new ResearchPlanDTO.ResearchBranch(
                    branchId2,
                    "Analyze product purchase trends",
                    List.of(
                        new ResearchPlanDTO.ResearchStep(
                            "step_1",
                            "Identify top-selling products",
                            "Aggregate order line items by product",
                            List.of(),
                            List.of(
                                new ResearchVariable("top10_revenue_share", "Revenue share of top 10 products", ResearchVariable.VariableType.NUMBER, new StepRef(branchId2, "step_1"))
                            )
                        ),
                        new ResearchPlanDTO.ResearchStep(
                            "step_2",
                            "Validate concentration metric stability",
                            "Recompute top10 share across monthly cohorts and check variance",
                            List.of(new StepRef(branchId2, "step_1")),
                            List.of()
                        )
                    ),
                    5,
                    ResearchPlanDTO.Priority.MEDIUM
                )
            ),
            12,
            List.of(
                "Identify customer segments with highest growth potential",
                "Understand churn drivers",
                "Identify product opportunities"
            ),
            Instant.now()
        );
    }

    private AnalysisResultDTO createMockAnalysisV1() {
        return new AnalysisResultDTO(
            "The business shows strong enterprise segment performance with 92% retention and high LTV ($12,450 avg), " +
            "but faces significant SMB churn (34% annually) driven by price sensitivity. Revenue concentration in top 10 products (67%) " +
            "presents both opportunity and risk.",
            List.of(
                new AnalysisResultDTO.Evidence(
                    "branch-customer-analysis",
                    "Enterprise segment has 92% retention with 3.2x higher LTV than other segments",
                    AnalysisResultDTO.EvidenceStrength.STRONG
                ),
                new AnalysisResultDTO.Evidence(
                    "branch-customer-analysis",
                    "SMB segment churns at 34% annually, primarily due to price sensitivity",
                    AnalysisResultDTO.EvidenceStrength.STRONG
                ),
                new AnalysisResultDTO.Evidence(
                    "branch-product-trends",
                    "Top 10 products generate 67% of revenue, indicating high concentration risk",
                    AnalysisResultDTO.EvidenceStrength.MODERATE
                )
            ),
            8.0,
            List.of("Churn could be seasonal rather than price-driven"),
            List.of(
                new AnalysisResultDTO.ResearchGap(
                    "No data on customer support interaction quality",
                    AnalysisResultDTO.GapImpact.MEDIUM,
                    "Customer support ticket data with satisfaction scores"
                )
            ),
            false,
            List.of(),
            List.of(
                "Customer email used as unique identifier (96% unique rate)",
                "Churn calculated as 12-month inactivity"
            ),
            Instant.now()
        );
    }

    private AnalysisResultDTO createMockAnalysisV2() {
        return new AnalysisResultDTO(
            "The business shows strong enterprise segment performance with 92% retention and high LTV ($12,450 avg), " +
            "but faces significant SMB churn (34% annually). Price sensitivity is the leading SMB churn hypothesis, " +
            "though seasonality remains a plausible contributor. Revenue concentration in top 10 products (67%) presents risk. " +
            "Prioritized actions are: (1) SMB pricing and packaging experiment, (2) retention playbook by segment, " +
            "(3) product portfolio diversification to reduce concentration risk.",
            List.of(
                new AnalysisResultDTO.Evidence(
                    "branch-customer-analysis",
                    "Enterprise segment has 92% retention with 3.2x higher LTV than other segments",
                    AnalysisResultDTO.EvidenceStrength.STRONG
                ),
                new AnalysisResultDTO.Evidence(
                    "branch-customer-analysis",
                    "SMB segment churns at 34% annually with strong correlation to discount sensitivity cohorts",
                    AnalysisResultDTO.EvidenceStrength.MODERATE
                ),
                new AnalysisResultDTO.Evidence(
                    "branch-product-trends",
                    "Top 10 products generate 67% of revenue, indicating high concentration risk",
                    AnalysisResultDTO.EvidenceStrength.MODERATE
                )
            ),
            8.5,
            List.of(
                "Churn could be seasonal rather than price-driven (requires multi-year data)",
                "Product concentration may be industry-standard (requires competitive benchmarking)"
            ),
            List.of(
                new AnalysisResultDTO.ResearchGap(
                    "No data on customer support interaction quality",
                    AnalysisResultDTO.GapImpact.MEDIUM,
                    "Customer support ticket data with satisfaction scores"
                ),
                new AnalysisResultDTO.ResearchGap(
                    "Product profit margins not analyzed",
                    AnalysisResultDTO.GapImpact.LOW,
                    "Cost data to calculate product-level profitability"
                )
            ),
            false,
            List.of(),
            List.of(
                "Customer email used as unique identifier (96% unique rate)",
                "Churn calculated as 12-month inactivity",
                "Revenue concentration based on 2024 data only"
            ),
            Instant.now()
        );
    }
}
