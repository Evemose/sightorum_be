package com.rorm.ai.swarm.dto;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@JsonClassDescription("Synthesized results from executing all steps in a research branch")
public record BranchExecutionResultDTO(

    @JsonPropertyDescription("""
        Goal this branch aimed to achieve.
        Copied from ResearchBranch.goal for context when reviewing results.
        """)
    @JsonProperty(required = true)
    String goal,

    @JsonPropertyDescription("""
        High-level summary synthesizing all step findings.
        Answers: What did this branch discover? How does it address the branch goal?
        Should be 2-3 sentences distilling 5-10 steps into core findings.
        Example: "Customer churn analysis revealed 68% of churned customers had unresolved 
        support tickets. Enterprise segment shows highest retention (92%) while SMB segment 
        churns at 34% annually. Late deliveries correlate with 2.3x higher churn risk."
        """)
    @JsonProperty(required = true)
    String branchSummary,

    @JsonPropertyDescription("""
        Key insights from this branch that inform overall research.
        Each insight should be actionable or reveal important patterns.
        Typically 2-4 insights per branch.
        Example: "Support experience is primary churn driver", "Enterprise retention strategies working"
        """)
    @JsonProperty(required = true)
    List<String> keyInsights,

    @JsonPropertyDescription("""
        Detailed results from each executed step in order.
        Contains full reasoning, actions, observations, and variables.
        Use for deep dive into how conclusions were reached.
        """)
    @JsonProperty(required = true)
    List<StepExecutionResultDTO> stepResults,

    @JsonPropertyDescription("""
        All variables produced by this branch (aggregated from all steps).
        Key = variable name, Value = computed result.
        Example: {"avg_churn_rate": "0.34", "top_churn_reason": "support_issues"}
        """)
    @JsonProperty(required = true)
    Map<String, String> producedVariables,

    @JsonPropertyDescription("When branch execution completed")
    @JsonProperty(required = true)
    Instant completedAt

) {
}