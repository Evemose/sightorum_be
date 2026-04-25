package com.rorm.ai.swarm.dto;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.jspecify.annotations.Nullable;

import java.util.List;

@JsonClassDescription("""
    Verification of hypothesis claims via ALTERNATIVE EVIDENCE computed
    with different conditioning, aggregation, or stratification. Each
    verification traces to a specific block in the claim source.
    
    Numeric conventions:
    - Cited numbers (claimedNumber, verifiedNumber) are carried as
      Strings with their unit inline: 'pp' for absolute percentage
      points, '%' for percent in [0, 100], 'x' for fold change, or a
      raw correlation magnitude in [-1, 1]. String form preserves the
      unit and avoids ambiguity between absolute and relative metrics.
    - Pattern identifiers are Strings, not ints, because cross-claim
      patterns use letter codes (C1-C5) and some verifications cite
      non-numbered patterns.""")
public record ScepticReviewDTO(

    @JsonPropertyDescription("""
        Per-claim verification blocks. Every block cites the source
        block name and pattern identifier, and every verdict is
        supported by a specific number from a specific computation.""")
    @JsonProperty(required = true)
    List<Verification> verifications,

    @JsonPropertyDescription("""
        Cross-claim findings about structural problems across multiple
        claims: claim dependency, evidence reuse, rare-event overlap,
        saturation asymmetry, aggregate inconsistency. Empty list if
        no structural problems were detected.""")
    @JsonProperty(required = true)
    List<CrossClaim> crossClaims,

    @JsonPropertyDescription("""
        Meta-observations about what the claim source reported or
        failed to report (noticed but unreported, misquoted,
        underspecified, internal contradiction). Empty list when none
        detected.""")
    @JsonProperty(required = true)
    List<Archaeology> archaeology,

    @JsonPropertyDescription("""
        End-of-output coverage summary listing every plan item and its
        final status.""")
    @JsonProperty(required = true)
    String coverageSummary
) {

    @JsonClassDescription("One verification block: a single alternative-evidence check against a claim.")
    public record Verification(

        @JsonPropertyDescription("""
            Reference to the source claim block, in the form
            '<block_name> #<pattern_id>'.""")
        @JsonProperty(required = true)
        String planItem,

        @JsonPropertyDescription("The claim being checked, in a single sentence.")
        @JsonProperty(required = true)
        String claim,

        @JsonPropertyDescription("""
            Cited number, run id, or evidence source that supports the
            claim. When a number is cited, include its unit inline.""")
        @JsonProperty(required = true)
        String claimedEvidence,

        @JsonPropertyDescription("""
            The alternative computation that was run, differing from the
            source in conditioning, aggregation, or stratification.
            Narrative description of the computation, not a raw query.""")
        @JsonProperty(required = true)
        String alternativePath,

        @JsonPropertyDescription("""
            Number from the original claim as a String with its unit
            inline. Unit suffixes: 'pp' for absolute percentage points,
            '%' for percent in [0, 100], 'x' for fold change, plain
            decimal for raw correlations in [-1, 1]. Null when the
            claim did not cite a specific number.""")
        @Nullable String claimedNumber,

        @JsonPropertyDescription("""
            Number recomputed on the alternative path, as a String with
            its unit inline. Same unit conventions as claimedNumber.
            Null when the check did not produce a comparable scalar.""")
        @Nullable String verifiedNumber,

        @JsonPropertyDescription("""
            Delta between claimedNumber and verifiedNumber as a String
            with sign and unit inline. Null when either side is null.""")
        @Nullable String delta,

        @JsonPropertyDescription("""
            Pattern identifier. Canonical numeric patterns as strings:
            '0' UNSUBSTANTIATED, '1' SCREENING_MEDIATION,
            '2' PROXY_ABSORPTION, '3' TREATMENT_DIRECTION,
            '4' EFFECT_MODIFIER, '5' ABSENCE_BELOW_DETECTION,
            '6' ECOLOGICAL_FALLACY, '7' COLLIDER_CONDITIONING,
            '8' SURVIVORSHIP_BIAS, '9' TEMPORAL_CONFOUNDING,
            '10' RARE_EVENT_TAUTOLOGY, '11' SAMPLE_SIZE_ADEQUACY,
            '12' CONFOUNDER_COMPLETENESS, '13' MISSING_DAG_EDGES,
            '14' COMPOSED_TREATMENT_DETECTION.
            Cross-claim patterns use letter codes: 'C1' DEPENDENCY,
            'C2' EVIDENCE_REUSE, 'C3' RARE_EVENT_OVERLAP,
            'C4' SATURATION_ASYMMETRY, 'C5' AGGREGATE_INCONSISTENCY.
            Ad-hoc patterns like 'threshold' are allowed when a check
            does not fit the canonical set. May be null when the
            pattern id is already encoded in planItem.""")
        @Nullable String pattern,

        @JsonPropertyDescription("""
            Verdict: SUPPORTED | INCOMPLETE | OVERSTATED | CONDITIONAL
            | CONTRADICTED. SUPPORTED = claim holds as stated;
            INCOMPLETE = claim holds but scope is narrower than stated;
            OVERSTATED = claim direction holds but magnitude is
            inflated; CONDITIONAL = claim holds in some strata,
            reverses in others; CONTRADICTED = claim fails on the
            alternative path.""")
        @JsonProperty(required = true)
        String verdict,

        @JsonPropertyDescription("""
            True when the finding materially changes the claim
            (downgrade, scope narrowing, DAG change). False when the
            finding is a confirmation that does not change anything.""")
        @JsonProperty(required = true)
        boolean material,

        @JsonPropertyDescription("""
            When material, the concrete change required. Null when
            material is false.""")
        @Nullable String executorNote
    ) {}

    @JsonClassDescription("Structural problem identified across multiple claims.")
    public record CrossClaim(

        @JsonPropertyDescription("""
            Type of structural problem: DEPENDENCY | EVIDENCE_REUSE |
            RARE_EVENT_OVERLAP | SATURATION_ASYMMETRY |
            AGGREGATE_INCONSISTENCY.""")
        @JsonProperty(required = true)
        String type,

        @JsonPropertyDescription("Block identifiers of the affected claims.")
        @JsonProperty(required = true)
        List<String> claimsInvolved,

        @JsonPropertyDescription("Description of the structural problem.")
        @JsonProperty(required = true)
        String finding,

        @JsonPropertyDescription("True when the finding materially changes one or more claims.")
        @JsonProperty(required = true)
        boolean material,

        @JsonPropertyDescription("When material, the concrete change required. Null otherwise.")
        @Nullable String executorNote
    ) {}

    @JsonClassDescription("""
        Meta-observation about what the claim source reported or failed
        to report, rather than an empirical refutation of a specific
        claim.""")
    public record Archaeology(

        @JsonPropertyDescription("""
            Finding type: NOTICED_BUT_UNREPORTED (source saw something
            but did not include it in the structured output) |
            MISQUOTED (source cited a number or threshold that does not
            match the underlying data) | UNDERSPECIFIED (source made a
            qualitative claim that was actually quantifiable) |
            CONTRADICTION (two parts of the source output disagree with
            each other).""")
        @JsonProperty(required = true)
        String type,

        @JsonPropertyDescription("""
            Location in the source output where the issue appears
            (section name or block identifier).""")
        @JsonProperty(required = true)
        String location,

        @JsonPropertyDescription("The observation: what was or was not reported.")
        @JsonProperty(required = true)
        String observation,

        @JsonPropertyDescription("""
            Why this finding matters: which later analytical step
            would otherwise draw the wrong conclusion from the source
            output.""")
        @JsonProperty(required = true)
        String impact
    ) {}
}
