package com.rorm.ai.swarm.dto;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.experimental.WithBy;

import java.time.Instant;
import java.util.List;

/**
 * Planner agent output - decomposed research strategy.
 * Defines hypotheses, branches, and steps for executors to follow.
 * Each branch tests a specific hypothesis about the user's question.
 */
@WithBy
@JsonClassDescription("Structured research plan with hypotheses, branches, and dependencies")
public record ResearchPlanDTO(

    @JsonPropertyDescription("""
        High-level research goal this plan aims to achieve.
        Example: "Identify factors contributing to customer churn in Q4 2024"
        """)
    @JsonProperty(required = true)
    String goal,

    @JsonPropertyDescription("""
        Hypotheses about the user's question that this plan will test.
        Each hypothesis is a falsifiable claim about why something is happening,
        what pattern exists, or what relationship holds in the data.
        Branches are designed to test these hypotheses.
        Example: ["Customer churn is driven primarily by poor support experience",
                  "Low-value customers churn at higher rates due to lower switching costs",
                  "Product category correlates with churn independently of customer value"]
        """)
    @JsonProperty(required = true)
    List<String> hypotheses,

    @JsonPropertyDescription("""
        Research branches that can be executed in parallel.
        Each branch investigates a specific aspect of the overall goal,
        typically testing one or more of the stated hypotheses.
        """)
    @JsonProperty(required = true)
    List<ResearchBranch> branches,

    @JsonPropertyDescription("""
        Estimated total complexity points across all branches.
        Helps gauge overall research effort. Sum of all branch complexities.
        """)
    @JsonProperty(required = true)
    int totalComplexity,

    @JsonPropertyDescription("""
        Success criteria for the overall research.
        List of specific questions that must be answered or conditions that must be met.
        Criteria should be insight-oriented, not just "compute X metric".
        Good: "Can we identify 2-3 actionable churn drivers with evidence distinguishing correlation from likely causation?"
        Bad: "What is the churn rate by segment?"
        """)
    @JsonProperty(required = true)
    List<String> successCriteria,

    @JsonPropertyDescription("Timestamp when plan was created")
    @JsonProperty(required = false)
    Instant timestamp

) {
    public enum Priority {
        HIGH, MEDIUM, LOW
    }

    @WithBy
    @JsonClassDescription("Single research branch investigating a specific aspect")
    public record ResearchBranch(
        @JsonPropertyDescription("Unique identifier for this branch (e.g., 'churn_analysis')")
        @JsonProperty(required = true)
        String branchId,

        @JsonPropertyDescription("What this branch aims to discover")
        @JsonProperty(required = true)
        String goal,

        @JsonPropertyDescription("""
            The hypothesis this branch tests. Must be a falsifiable claim.
            Good: "Customers who contact support 3+ times without resolution are 5x more likely to churn"
            Bad: "Support affects churn" (not specific, not falsifiable)
            """)
        @JsonProperty(required = true)
        String hypothesis,

        @JsonPropertyDescription("""
            What evidence would disprove the hypothesis?
            This forces rigorous thinking about what the branch actually tests.
            Example: "If churned customers show similar or fewer unresolved tickets than retained customers"
            """)
        @JsonProperty(required = true)
        String nullCondition,

        @JsonPropertyDescription("""
            Known confounding variables that could explain results even if hypothesis appears confirmed.
            Steps should attempt to control for these.
            Example: ["Customer tenure (longer-tenured customers may both use support more AND churn less)",
                      "Product complexity (complex products generate more tickets regardless of churn risk)"]
            """)
        @JsonProperty(required = false)
        List<String> confounds,

        @JsonPropertyDescription("""
            Ordered steps to execute in this branch.
            Each step builds on previous steps' findings.
            """)
        @JsonProperty(required = true)
        List<ResearchStep> steps,

        @JsonPropertyDescription("""
            Estimated complexity of this branch: 1 (simple) to 10 (very complex).
            Based on number of entities, joins, expected data volume, quality issues.
            """)
        @JsonProperty(required = true)
        int complexity,

        @JsonPropertyDescription("""
            Priority: HIGH (critical to research goal), MEDIUM (important), LOW (nice-to-have).
            If resources are limited, high-priority branches execute first.
            """)
        @JsonProperty(required = true)
        Priority priority
    ) {}

    @WithBy
    @JsonClassDescription("Single research step within a branch")
    public record ResearchStep(
        @JsonPropertyDescription("Unique step identifier within the branch (e.g., 'step_1')")
        @JsonProperty(required = true)
        String stepId,

        @JsonPropertyDescription("""
            What this step aims to accomplish or discover.
            Should describe an analytical milestone, not just a mechanical operation.
            Good: "Determine whether support ticket volume predicts churn independently of customer value tier"
            Bad: "Count support tickets grouped by churn status"
            """)
        @JsonProperty(required = true)
        String objective,

        @JsonPropertyDescription("""
            Analytical reasoning the Executor should follow. Describe WHAT to investigate
            and WHY, not just which tools to call. Executor translates reasoning into queries.
            Good: "Compare unresolved ticket rates between churned and retained customers,
                   controlling for customer value tier. If the relationship holds across
                   all tiers, support quality is an independent driver. If it only appears
                   in low-value tier, it may be confounded with value."
            Bad: "Use executeQuery with GROUP BY status, calculate COUNT(*)"
            """)
        @JsonProperty(required = true)
        String suggestedApproach,

        @JsonPropertyDescription("""
            Dependencies on other steps (within same branch or across branches).
            Step cannot execute until all prerequisite steps complete.
            All steps but first of each branch implicitly depend on previous step in same branch.
            Empty list means step can execute immediately after previous step of branch is completed.
            """)
        @JsonProperty(required = true)
        List<StepRef> dependencies,

        @JsonPropertyDescription("""
            Variables this step will produce.
            Each variable has a name, description, and type for context and validation.
            """)
        @JsonProperty(required = true)
        List<ResearchVariable> outputs
    ) {}
}