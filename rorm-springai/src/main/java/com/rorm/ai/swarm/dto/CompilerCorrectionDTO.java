package com.rorm.ai.swarm.dto;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.jspecify.annotations.Nullable;

import java.util.List;

@JsonClassDescription("""
    Mutation-driven verification of a compiled PipelineSpec against its
    engine output. Each verification traces a compiler decision to a
    measured delta from one or more replay rounds. Verdicts are grounded
    in observed stage-level changes, not static readings.""")
public record CompilerCorrectionDTO(

    @JsonPropertyDescription("""
        Per-field verification blocks. Every block traces a compiler
        decision to a measured delta via replay. Ordered by material
        impact (material findings first).""")
    @JsonProperty(required = true)
    List<Verification> verifications,

    @JsonPropertyDescription("""
        End-of-output coverage summary listing every plan item and its
        final status.""")
    @JsonProperty(required = true)
    String coverageSummary
) {

    @JsonClassDescription("""
        One verification block: a single compiler decision tested via
        replay mutation and measured delta.""")
    public record Verification(

        @JsonPropertyDescription("""
            Reference to the spec field and pattern number, in the form
            '<spec_field> #<pattern>'.""")
        @JsonProperty(required = true)
        String planItem,

        @JsonPropertyDescription("The compiler's decision and its stated grounding.")
        @JsonProperty(required = true)
        String claim,

        @JsonPropertyDescription("""
            What was computed — recomputation target, cross-reference
            anchor, or comparison across re-executed runs.""")
        @JsonProperty(required = true)
        String alternativePath,

        @JsonPropertyDescription("The value stated in the PipelineSpec.")
        @JsonProperty(required = true)
        String compilerValue,

        @JsonPropertyDescription("The value the computation or cross-reference produced.")
        @JsonProperty(required = true)
        String alternativeValue,

        @JsonPropertyDescription("""
            Difference in interpretable units — pp, count, ratio, sign
            flip, CI inclusion of null, tier change, flag flipped, or
            CITATION_MISMATCH for cross-reference findings.""")
        @JsonProperty(required = true)
        String delta,

        @JsonPropertyDescription("""
            Short rule-triggered labels: FORMULA_DRIFT, TIER_CHANGE,
            SIGN_FLIP, GATE_FLIP_nuisance_treatment,
            REFUTATION_LOST_placebo, E_VALUE_COLLAPSE, SILENT_DRIFT,
            etc. Drawn from the pattern verdict vocabulary plus observed
            deltas.""")
        @JsonProperty(required = true)
        List<String> markers,

        @JsonPropertyDescription("""
            SUPPORTED — compiler decision holds under replay.
            CONTRADICTED — replay delta invalidates the decision.
            INCOMPLETE — decision holds but scope is narrower.
            UNGROUNDED — compiler cited evidence that does not exist
            in the engine output or data.
            DISCHARGED — suspicion tested, no material delta found.""")
        @JsonProperty(required = true)
        String verdict,

        @JsonPropertyDescription("""
            True when the finding affects the primary ATE, a gate flag,
            a refutation verdict, or tier assignment.""")
        @JsonProperty(required = true)
        boolean material,

        @JsonPropertyDescription("""
            One-to-three-sentence interpretation: which compiler decision
            drove the observed delta, what mechanism makes the compiler's
            value wrong, and what the corrected configuration
            accomplishes.""")
        @JsonProperty(required = true)
        String annotation,

        @JsonPropertyDescription("""
            When material, the concrete spec diff that should be applied.
            Null when not material.""")
        @Nullable String compilerNote
    ) {}
}
