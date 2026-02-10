package com.rorm.ai.swarm.dto;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.time.Instant;
import java.util.List;

/**
 * Analyzer agent output - synthesis of all branch findings.
 * Produces final research conclusion with evidence and confidence.
 */
@JsonClassDescription("Final analysis synthesizing all research findings")
public record AnalysisResultDTO(

    @JsonPropertyDescription("""
        Primary conclusion from the research.
        Directly answers the original research question with concrete findings.
        Example: "Customer churn is primarily driven by poor support experience (45% of churned
        customers had 3+ unresolved tickets) and price sensitivity in budget segment (30% churned
        after price increase). Enterprise segment retention strategies are effective (92% retention)."
        """)
    @JsonProperty(required = true)
    String mainConclusion,

    @JsonPropertyDescription("""
        Supporting evidence from research findings.
        Each piece references specific branch discoveries with context.
        Example: "Branch churn_analysis: 68% of churned customers had unresolved support tickets"
        """)
    @JsonProperty(required = true)
    List<Evidence> supportingEvidence,

    @JsonPropertyDescription("""
        Overall confidence in the conclusion (0.0-10.0).
        Based on data quality, sample size, consistency across branches, evidence strength.
        7.0+ = high confidence, 5.0-7.0 = moderate, <5.0 = low confidence
        """)
    @JsonProperty(required = true)
    double confidence,

    @JsonPropertyDescription("""
        Alternative interpretations or explanations considered.
        Shows analytical depth and intellectual honesty.
        Example: "Churn could also be seasonal (lower in Q1), but data only spans 6 months"
        """)
    @JsonProperty(required = true)
    List<String> alternatives,

    @JsonPropertyDescription("""
        Gaps or limitations in the research.
        What questions remain unanswered or what data was unavailable.
        """)
    @JsonProperty(required = true)
    List<ResearchGap> gaps,

    @JsonPropertyDescription("""
        Whether additional research is recommended to resolve gaps or increase confidence.
        If true, specific follow-up investigations should be listed in suggestedFollowUp.
        """)
    @JsonProperty(required = true)
    boolean needsMoreResearch,

    @JsonPropertyDescription("""
        If needsMoreResearch=true, list specific investigations to conduct.
        Each should be actionable and address specific gaps.
        Example: "Analyze support ticket sentiment to confirm correlation with churn"
        """)
    @JsonProperty(required = false)
    List<String> suggestedFollowUp,

    @JsonPropertyDescription("""
        Key assumptions made during analysis.
        Example: "Assumes customer email as unique identifier (96% unique rate)"
        """)
    @JsonProperty(required = true)
    List<String> assumptions,

    @JsonPropertyDescription("Timestamp when analysis completed")
    @JsonProperty(required = true)
    Instant timestamp

) {
    public enum EvidenceStrength {
        STRONG, MODERATE, WEAK
    }

    public enum GapImpact {
        HIGH, MEDIUM, LOW
    }

    @JsonClassDescription("Evidence supporting the main conclusion")
    public record Evidence(
        @JsonPropertyDescription("Which branch produced this evidence (e.g., 'churn_analysis')")
        @JsonProperty(required = true)
        String sourceBranch,

        @JsonPropertyDescription("The specific finding or observation")
        @JsonProperty(required = true)
        String finding,

        @JsonPropertyDescription("""
            Strength of this evidence:
            STRONG = direct proof, causal relationship established
            MODERATE = strong correlation, compelling pattern
            WEAK = suggestive but not conclusive
            """)
        @JsonProperty(required = true)
        EvidenceStrength strength
    ) {}

    @JsonClassDescription("Gap in the research that limits confidence")
    public record ResearchGap(
        @JsonPropertyDescription("What information is missing")
        @JsonProperty(required = true)
        String description,

        @JsonPropertyDescription("""
            Impact on conclusion reliability:
            HIGH = major concern, significantly limits confidence
            MEDIUM = notable limitation, somewhat reduces confidence
            LOW = minor caveat, minimal impact on conclusion
            """)
        @JsonProperty(required = true)
        GapImpact impact,

        @JsonPropertyDescription("What would be needed to fill this gap")
        @JsonProperty(required = false)
        String requiredData
    ) {}
}