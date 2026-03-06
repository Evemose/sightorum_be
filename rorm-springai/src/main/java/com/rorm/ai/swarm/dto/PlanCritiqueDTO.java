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
    /**
     * Challenge types organized into two categories:
     * <p>
     * Conceptual (research design quality):
     * FLAWED_HYPOTHESIS, CONFOUNDED_ANALYSIS, CAUSAL_OVERCLAIM,
     * MISSING_CONTROL_GROUP, SELECTION_BIAS, ECOLOGICAL_FALLACY,
     * WEAK_RESEARCH_DESIGN
     * <p>
     * Structural (plan mechanics):
     * MISSING_BRANCH, UNCLEAR_OBJECTIVE, INFEASIBLE_STEP,
     * WRONG_DEPENDENCY, REDUNDANT, SCOPE_CREEP,
     * INCOMPLETE_DECOMPOSITION, IGNORES_PREVIOUS_FINDINGS,
     * REQUIRES_ITERATION, VAGUE_APPROACH, TOOL_MISMATCH
     */
    public enum PlanChallengeType {
        // --- Conceptual challenges (research design quality) ---

        /**
         * Branch hypothesis is unfalsifiable, circular, or poorly framed
         */
        FLAWED_HYPOTHESIS,

        /**
         * Analysis doesn't control for confounding variables that could explain results
         */
        CONFOUNDED_ANALYSIS,

        /**
         * Plan assumes causal conclusions from correlational design without justification
         */
        CAUSAL_OVERCLAIM,

        /**
         * No baseline or comparison group defined to contextualize findings
         */
        MISSING_CONTROL_GROUP,

        /**
         * Filtering or sampling strategy introduces systematic bias into results
         */
        SELECTION_BIAS,

        /**
         * Plan draws individual-level conclusions from aggregate data or vice versa
         */
        ECOLOGICAL_FALLACY,

        /**
         * Overall research logic is weak: branches don't test meaningful hypotheses,
         * steps don't build toward insight, or analytical reasoning is shallow
         */
        WEAK_RESEARCH_DESIGN,

        // --- Structural challenges (plan mechanics) ---

        /**
         * Key aspect of the query is not investigated by any branch
         */
        MISSING_BRANCH,

        /**
         * Step goal is vague, ambiguous, or not measurable
         */
        UNCLEAR_OBJECTIVE,

        /**
         * Step cannot be accomplished with available data or tools
         */
        INFEASIBLE_STEP,

        /**
         * Incorrect, missing, or circular dependency reference
         */
        WRONG_DEPENDENCY,

        /**
         * Duplicate work across steps or branches
         */
        REDUNDANT,

        /**
         * Plan is too ambitious or wanders beyond what was asked
         */
        SCOPE_CREEP,

        /**
         * Not broken down enough for single-execution steps
         */
        INCOMPLETE_DECOMPOSITION,

        /**
         * Plan ignores or conflicts with Scout or previous agent findings
         */
        IGNORES_PREVIOUS_FINDINGS,

        /**
         * Step requires multiple attempts or iterative refinement
         */
        REQUIRES_ITERATION,

        /**
         * Suggested approach is too vague for Executor to act on
         */
        VAGUE_APPROACH,

        /**
         * Suggests tools that don't exist or misuses available tools
         */
        TOOL_MISMATCH
    }

    public enum ModificationType {
        ADD_BRANCH, REMOVE_BRANCH, MODIFY_STEP, CLARIFY_OBJECTIVE,
        REORDER_DEPENDENCIES, MERGE_BRANCHES,
        REFORMULATE_HYPOTHESIS, ADD_CONTROL_GROUP, ADD_CONFOUND_CONTROL
    }

    public enum Severity {
        CRITICAL, HIGH, MEDIUM, LOW
    }

    public enum RiskLevel {
        LOW, MEDIUM, HIGH
    }

    @JsonClassDescription("Challenge to research plan structure or strategy")
    public record PlanChallenge(
        @JsonPropertyDescription("Which branch or step is challenged (e.g. 'branch:churn_analysis' or 'branch:churn_analysis:step:step_2')")
        @JsonProperty(required = true)
        String targetElement,

        @JsonPropertyDescription("""
            Challenge type. Conceptual types (research design):
            FLAWED_HYPOTHESIS (unfalsifiable/circular hypothesis),
            CONFOUNDED_ANALYSIS (uncontrolled confounding variables),
            CAUSAL_OVERCLAIM (assumes causation from correlation),
            MISSING_CONTROL_GROUP (no baseline comparison),
            SELECTION_BIAS (biased sampling/filtering),
            ECOLOGICAL_FALLACY (wrong level of analysis),
            WEAK_RESEARCH_DESIGN (shallow analytical reasoning).
            
            Structural types (plan mechanics):
            MISSING_BRANCH (strategy gap), UNCLEAR_OBJECTIVE (vague goal),
            INFEASIBLE_STEP (can't be executed), WRONG_DEPENDENCY (incorrect order),
            REDUNDANT (duplicates other branch), SCOPE_CREEP (out of scope),
            INCOMPLETE_DECOMPOSITION (needs further breakdown),
            IGNORES_PREVIOUS_FINDINGS (missed or conflicts with discovered fact),
            REQUIRES_ITERATION (needs multiple attempts),
            VAGUE_APPROACH (insufficient guidance for Executor),
            TOOL_MISMATCH (wrong tool usage)
            """)
        @JsonProperty(required = true)
        PlanChallengeType type,

        @JsonPropertyDescription("Explanation of the concern")
        @JsonProperty(required = true)
        String issue,

        @JsonPropertyDescription("How to fix: reformulate hypothesis, add control group, clarify step, etc.")
        @JsonProperty(required = true)
        String suggestedFix,

        @JsonPropertyDescription("Severity: CRITICAL (plan will fail), HIGH (major issue), MEDIUM (notable), LOW (minor)")
        @JsonProperty(required = true)
        Severity severity
    ) {}

    @JsonClassDescription("Specific modification needed to approve plan")
    public record PlanModification(
        @JsonPropertyDescription("""
            ADD_BRANCH, REMOVE_BRANCH, MODIFY_STEP, CLARIFY_OBJECTIVE,
            REORDER_DEPENDENCIES, MERGE_BRANCHES,
            REFORMULATE_HYPOTHESIS, ADD_CONTROL_GROUP, ADD_CONFOUND_CONTROL
            """)
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