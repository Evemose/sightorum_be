package com.rorm.ai.swarm.dto;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.experimental.WithBy;

import java.time.Instant;
import java.util.List;

/**
 * Planner agent output - decomposed research strategy.
 * Defines branches and steps for executors to follow.
 */
@WithBy
@JsonClassDescription("Structured research plan with branches and dependencies")
public record ResearchPlanDTO(

    @JsonPropertyDescription("""
        High-level research goal this plan aims to achieve.
        Example: "Identify factors contributing to customer churn in Q4 2024"
        """)
    @JsonProperty(required = true)
    String goal,

    @JsonPropertyDescription("""
        Research branches that can be executed in parallel.
        Each branch investigates a specific aspect of the overall goal.
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
        """)
    @JsonProperty(required = true)
    List<String> successCriteria,

    @JsonPropertyDescription("Timestamp when plan was created")
    @JsonProperty(required = true)
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
            Example: "Calculate average order value by customer segment"
            """)
        @JsonProperty(required = true)
        String objective,

        @JsonPropertyDescription("""
            Suggested approach or tools to use.
            Example: "Use SQL aggregation on orders table grouped by customer segment"
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