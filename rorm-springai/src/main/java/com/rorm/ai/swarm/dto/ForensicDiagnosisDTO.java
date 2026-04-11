package com.rorm.ai.swarm.dto;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.jspecify.annotations.Nullable;

@JsonClassDescription("""
    Post-mortem diagnosis for a null hypothesis: explains WHY the effect
    is null (not THAT it is null), distinguishing a real mechanism that
    is undetectable, a confounded-away association, a genuine absence,
    and an underpowered check.

    Numeric conventions for keyNumbers:
    - Effect sizes (marginalEffect, residualEffect, mde80Power): decimal
      fractions on the outcome's scale. For a binary outcome these are
      absolute probability differences in [-1, 1]; -0.0061 means 'a
      0.61 percentage-point reduction'. Text like '-0.61pp' or '-0.61%'
      MUST be converted to -0.0061 before storage.
    - absorptionPct: decimal fraction in [0, 1]. 0.90 means '90% of the
      marginal signal was absorbed'. Text like '90%' MUST be divided
      by 100 before storage.
    - observedVsMdeRatio: dimensionless ratio, >= 0. 0.053 means
      'observed effect is about 5.3% of the MDE'. Never a percent.""")
public record ForensicDiagnosisDTO(

    @JsonPropertyDescription("Hypothesis identifier being diagnosed, e.g. 'H1'.")
    @JsonProperty(required = true)
    String hypothesisId,

    @JsonPropertyDescription("""
        Primary null classification: DOMINATED_MECHANISM | CONFOUNDED_AWAY
        | UNDERPOWERED | GENUINELY_ABSENT | THRESHOLD_CONDITIONAL.""")
    @JsonProperty(required = true)
    String primaryClassification,

    @JsonPropertyDescription("""
        Optional secondary classification when the null has more than one
        contributing diagnosis. Null when a single class explains the
        finding.""")
    @Nullable String secondaryClassification,

    @JsonPropertyDescription("""
        2-4 sentence summary of the classification and the reasoning
        chain that leads to it, citing specific numbers from the
        absorption curve, power analysis, and subpopulation edges.""")
    @JsonProperty(required = true)
    String classificationSummary,

    @JsonPropertyDescription("""
        Absorption narrative, 1-3 paragraphs translating the absorption
        curve into a causal story. Must identify which variables
        absorbed the most signal, classify the primary absorber as
        mediator / confounder / competing mechanism, and connect the
        absorption pattern to the null classification. Narrative, not
        a step list.""")
    @JsonProperty(required = true)
    String absorptionNarrative,

    @JsonPropertyDescription("""
        Mechanism assessment, 1-3 paragraphs: is the treatment's causal
        mechanism plausible despite the null? What does domain knowledge
        say? If the mechanism is real, why is it undetectable (dominated,
        too small, wrong population)? Are there W-matrix or GRF variables
        capturing the same physical dimension? Commits to an
        interpretation; does not hedge.""")
    @JsonProperty(required = true)
    String mechanismAssessment,

    @JsonPropertyDescription("""
        Per-edge assessment of subpopulation edges. For barely-significant
        edges: is the subpopulation effect causally interpretable or
        likely a multiple-comparisons artifact? For barely-insignificant
        edges: dismiss or flag for further investigation. When none
        exist, an explicit 'none detected' statement.""")
    @JsonProperty(required = true)
    String subpopulationEdges,

    @JsonPropertyDescription("""
        Specific conditions under which the hypothesis might produce a
        positive finding (different population, different treatment
        operationalization, different data) or an explicit 'null is
        robust' statement. Prevents false closure.""")
    @JsonProperty(required = true)
    String whatWouldChangeVerdict,

    @JsonPropertyDescription("Key numbers extracted verbatim from the evidence.")
    @JsonProperty(required = true)
    KeyNumbers keyNumbers
) {

    @JsonClassDescription("""
        Numerical summary of the absorption curve, power analysis, and
        subpopulation edges. Every value comes directly from the
        evidence; no estimation or extrapolation.""")
    public record KeyNumbers(

        @JsonPropertyDescription("Step 0 marginal effect from the absorption curve.")
        @Nullable Double marginalEffect,

        @JsonPropertyDescription("Name of the variable that absorbed the most signal.")
        @Nullable String primaryAbsorber,

        @JsonPropertyDescription("Fraction of marginal signal absorbed by the primary absorber. Decimal in [0, 1].")
        @Nullable Double absorptionPct,

        @JsonPropertyDescription("Residual effect after the full W matrix is applied.")
        @Nullable Double residualEffect,

        @JsonPropertyDescription("Minimum detectable effect at 80% power from the power analysis.")
        @Nullable Double mde80Power,

        @JsonPropertyDescription("Observed effect / MDE ratio. Dimensionless ratio, >= 0.")
        @Nullable Double observedVsMdeRatio,

        @JsonPropertyDescription("""
            Summary of subpopulation edges: count and strongest (e.g.
            '2 edges, strongest: cool_zone barely_significant at
            -0.8pp').""")
        @Nullable String subpopulationEdgesSummary
    ) {}
}
