package com.rorm.ai.swarm.dto;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.time.Instant;
import java.util.List;

@JsonClassDescription("Critical review of research plan before execution")
public record PlanCritiqueDTO(

    @JsonPropertyDescription("Challenges to the research strategy")
    @JsonProperty(required = true)
    List<PlanChallenge> challenges,

    @JsonPropertyDescription("Strengths of the plan")
    @JsonProperty(required = true)
    List<String> strengths,

    @JsonPropertyDescription("""
        Plan quality score (0.0-10.0).
        0-4: Unacceptable (critical flaws)
        5-6: Poor (major issues)
        7-8: Acceptable (minor issues)
        9-10: Excellent
        """)
    @JsonProperty(required = true)
    double planScore,

    @JsonPropertyDescription("Overall assessment reasoning")
    @JsonProperty(required = true)
    String reasoning,

    @JsonPropertyDescription("Required plan modifications if not approved")
    @JsonProperty(required = false)
    List<PlanModification> requiredModifications,

    @JsonPropertyDescription("Risk if plan is executed as-is: LOW, MEDIUM, HIGH")
    @JsonProperty(required = true)
    RiskLevel risk,

    @JsonPropertyDescription("Timestamp of review")
    @JsonProperty(required = true)
    Instant timestamp
) {
    public enum PlanChallengeType {
        MISSING_BRANCH, UNCLEAR_OBJECTIVE, INFEASIBLE_STEP,
        WRONG_DEPENDENCY, REDUNDANT, SCOPE_CREEP, INCOMPLETE_DECOMPOSITION
    }

    public enum ModificationType {
        ADD_BRANCH, REMOVE_BRANCH, MODIFY_STEP, CLARIFY_OBJECTIVE,
        REORDER_DEPENDENCIES, MERGE_BRANCHES
    }

    public enum Severity {
        CRITICAL, HIGH, MEDIUM, LOW
    }

    public enum RiskLevel {
        LOW, MEDIUM, HIGH
    }

    @JsonClassDescription("Challenge to research plan structure or strategy")
    public record PlanChallenge(
        @JsonPropertyDescription("Which branch or step is challenged")
        @JsonProperty(required = true)
        String targetElement,

        @JsonPropertyDescription("""
            Type: MISSING_BRANCH (strategy gap), UNCLEAR_OBJECTIVE (vague goal),
            INFEASIBLE_STEP (can't be executed), WRONG_DEPENDENCY (incorrect order),
            REDUNDANT (duplicates other branch), SCOPE_CREEP (out of scope)
            """)
        @JsonProperty(required = true)
        PlanChallengeType type,

        @JsonPropertyDescription("Explanation of the concern")
        @JsonProperty(required = true)
        String issue,

        @JsonPropertyDescription("How to fix: add branch, clarify step, reorder, etc.")
        @JsonProperty(required = true)
        String suggestedFix,

        @JsonPropertyDescription("Severity: CRITICAL (plan will fail), HIGH (major issue), MEDIUM (notable), LOW (minor)")
        @JsonProperty(required = true)
        Severity severity
    ) {}

    @JsonClassDescription("Specific modification needed to approve plan")
    public record PlanModification(
        @JsonPropertyDescription("ADD_BRANCH, REMOVE_BRANCH, MODIFY_STEP, CLARIFY_OBJECTIVE, REORDER_DEPENDENCIES")
        @JsonProperty(required = true)
        ModificationType type,

        @JsonPropertyDescription("Which element to modify")
        @JsonProperty(required = true)
        String target,

        @JsonPropertyDescription("What the modification should achieve")
        @JsonProperty(required = true)
        String description
    ) {}
}