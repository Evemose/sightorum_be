package com.rorm.ai.swarm.dto;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.time.Instant;
import java.util.List;

/**
 * Critic agent output - challenges to analyzer's conclusion.
 * Ensures intellectual rigor through adversarial review.
 */
@JsonClassDescription("Critical review of analysis with challenges and recommendations")
public record ConclusionCritiqueDTO(

    @JsonPropertyDescription("""
        Conclusion quality score (0.0-10.0).
        0-4: Unacceptable (critical flaws, unsupported claims)
        5-6: Poor (major logical issues, insufficient evidence)
        7-8: Acceptable (minor issues, generally sound)
        9-10: Excellent (rigorous, well-supported, comprehensive)
        """)
    @JsonProperty(required = true)
    double conclusionScore,

    @JsonPropertyDescription("""
        Specific challenges to the analysis.
        Each challenge cites evidence and explains the logical concern.
        """)
    @JsonProperty(required = true)
    List<Challenge> challenges,

    @JsonPropertyDescription("""
        Strengths identified in the analysis.
        Acknowledges sound reasoning and solid evidence.
        """)
    @JsonProperty(required = true)
    List<String> strengths,

    @JsonPropertyDescription("""
        Overall assessment reasoning.
        Balances challenges against strengths to reach score.
        Explains why score was assigned.
        """)
    @JsonProperty(required = true)
    String reasoning,

    @JsonPropertyDescription("""
        If score < 7.0, what must be done to improve.
        Specific actions like "Verify data quality of X" or "Consider alternative Y"
        """)
    @JsonProperty(required = false)
    List<String> requiredImprovements,

    @JsonPropertyDescription("""
        Overall risk level if conclusion is accepted as-is.
        LOW = minor caveats only
        MEDIUM = notable concerns, proceed with caution
        HIGH = major issues, should not proceed without revision
        """)
    @JsonProperty(required = true)
    RiskLevel risk,

    @JsonPropertyDescription("Timestamp of review")
    @JsonProperty(required = true)
    Instant timestamp

) {
    public enum ChallengeType {
        LOGICAL_FLAW, INSUFFICIENT_EVIDENCE, ALTERNATIVE_EXPLANATION,
        CORRELATION_NOT_CAUSATION, DATA_QUALITY, SAMPLE_SIZE,
        BIAS, METHODOLOGICAL_ERROR, OVERGENERALIZATION
    }

    public enum Severity {
        CRITICAL, HIGH, MEDIUM, LOW
    }

    public enum RiskLevel {
        LOW, MEDIUM, HIGH
    }

    @JsonClassDescription("Specific challenge to a finding or reasoning")
    public record Challenge(
        @JsonPropertyDescription("Which conclusion or finding is being challenged")
        @JsonProperty(required = true)
        String targetFinding,

        @JsonPropertyDescription("""
            Type of challenge:
            LOGICAL_FLAW = reasoning error, invalid inference
            INSUFFICIENT_EVIDENCE = claim not adequately supported
            ALTERNATIVE_EXPLANATION = other interpretation equally valid
            CORRELATION_NOT_CAUSATION = causal claim from correlational data
            DATA_QUALITY = reliability concern with underlying data
            SAMPLE_SIZE = insufficient data for generalization
            BIAS = confirmation bias or cherry-picking
            METHODOLOGICAL_ERROR = flawed approach or analysis
            OVERGENERALIZATION = claim exceeds what data supports
            """)
        @JsonProperty(required = true)
        ChallengeType type,

        @JsonPropertyDescription("Explanation of the concern")
        @JsonProperty(required = true)
        String issue,

        @JsonPropertyDescription("""
            What would resolve this challenge.
            Specific evidence or analysis needed.
            Example: "Show distribution of support tickets across all customers, not just churned"
            """)
        @JsonProperty(required = true)
        String resolution,

        @JsonPropertyDescription("""
            Severity:
            CRITICAL = invalidates core conclusion
            HIGH = major concern affecting key findings
            MEDIUM = notable limitation requiring acknowledgment
            LOW = minor caveat, doesn't affect main conclusion
            """)
        @JsonProperty(required = true)
        Severity severity
    ) {}
}