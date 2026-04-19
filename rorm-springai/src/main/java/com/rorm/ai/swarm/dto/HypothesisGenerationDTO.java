package com.rorm.ai.swarm.dto;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.jspecify.annotations.Nullable;

import java.util.List;

@JsonClassDescription("""
    Generator output: causal hypotheses plus below-detection thresholds, domain
    discrepancies, rare-event findings, and interaction candidates.
    
    Numeric conventions:
    - Rates, proportions, probabilities (outcomeRate*, pctOfTotal,
      selectionFrequency, absorptionPct): decimal fractions in [0, 1].
      '6.5%' is stored as 0.065.
    - Rate ratios (rateRatio): dimensionless multipliers, >= 0. '12.2x' is
      stored as 12.2. Never a fraction.
    - Effect sizes, raw rates, and n-per-level carried in String fields MUST
      include their unit inline: 'pp' for absolute percentage points, '%' for
      percent in [0, 100], 'x' for fold change.
    - Row counts (n): integer counts, no unit suffix.""")
public record HypothesisGenerationDTO(

    @JsonPropertyDescription("""
        One hypothesis per distinct causal mechanism. Multiple features
        operating through the same pathway are bundled as one hypothesis with
        the most upstream variable as treatment.""")
    @JsonProperty(required = true)
    List<Hypothesis> hypotheses,

    @JsonPropertyDescription("""
        Seed attributes that showed no signal after saturation, each with
        an absence hypothesis and a recommended follow-up analysis.
        Empty list if none.""")
    @JsonProperty(required = true)
    List<BelowDetectionThreshold> belowDetectionThresholds,

    @JsonPropertyDescription("""
        Divergences between domain expectations and empirical findings, each
        with a proposed resolution. Empty list if none.""")
    @JsonProperty(required = true)
    List<DomainDiscrepancy> domainDiscrepancies,

    @JsonPropertyDescription("""
        Low-frequency, high-impact conditions flagged during the rare-event
        scan. Empty list if none.""")
    @JsonProperty(required = true)
    List<RareEventFinding> rareEventFindings,

    @JsonPropertyDescription("""
        Suspected interactions between anchor-internal variables that appear
        to matter jointly. Empty list if none.""")
    @JsonProperty(required = true)
    List<InteractionCandidate> interactionCandidates
) {

    @JsonClassDescription("""
        One causal hypothesis. Treatment must be a single source column from
        the anchor entity or a reachable enrichment entity (no CONCAT or
        composed treatments).""")
    public record Hypothesis(

        @JsonPropertyDescription("""
            Hypothesis title, e.g. 'H1'. MUST exactly match the `title:`
            value on the second non-blank line inside the HYPOTHESIS fence
            in the raw source — this string is the deterministic key used
            to slice the raw block back out of the generator output.""")
        @JsonProperty(required = true)
        String title,

        @JsonPropertyDescription("Treatment column name. Must be a single source column.")
        @JsonProperty(required = true)
        String treatment,

        @JsonPropertyDescription("Anchor or enrichment entity that owns the treatment column.")
        @JsonProperty(required = true)
        String treatmentScope,

        @JsonPropertyDescription("One of: continuous | binary_threshold | categorical.")
        @JsonProperty(required = true)
        String treatmentForm,

        @JsonPropertyDescription("""
            Numeric breakpoint value when treatmentForm is binary_threshold.
            Null otherwise.""")
        @Nullable Double thresholdValue,

        @JsonPropertyDescription("""
            Breakpoint convergence in the form '<N converged>/<N total>'
            (e.g. '48/50'). Null when treatmentForm is not binary_threshold.""")
        @Nullable String thresholdConvergence,

        @JsonPropertyDescription("Outcome column name.")
        @JsonProperty(required = true)
        String outcome,

        @JsonPropertyDescription("""
            Expected sign of the effect. Format: the literal '+1' or '-1' for
            numeric treatments. For categorical treatments use an ordering of
            the form '<level_A> -> <effect_A>; <level_B> -> <effect_B>'.
            Scope qualifiers (populations where the sign flips, regime
            boundaries) may be appended inline.""")
        @JsonProperty(required = true)
        String expectedDirection,

        @JsonPropertyDescription("Columns that modify the treatment effect (empty list if none).")
        @JsonProperty(required = true)
        List<String> effectModifiers,

        @JsonPropertyDescription("""
            Columns on the directed path from treatment to outcome that are
            themselves independently actionable. Empty list if none.""")
        @JsonProperty(required = true)
        List<String> independentlyActionableMediators,

        @JsonPropertyDescription("""
            Anchor entity, reachable enrichment entities, iteration chain of
            stability-selection runs, and the saturated mechanisms.""")
        @JsonProperty(required = true)
        AnchorGrounding anchorGrounding,

        @JsonPropertyDescription("""
            DAG edges for this hypothesis, each with from, to, and causal
            reasoning.""")
        @JsonProperty(required = true)
        List<DagEdge> dagEdges,

        @JsonPropertyDescription("""
            Narrative explaining each confounder's role: the plausible causal
            path and whether it is upstream of treatment, outcome, or both.""")
        @JsonProperty(required = true)
        String confounderReasoning,

        @JsonPropertyDescription("Enrichment joins required to materialize confounders (empty list if none).")
        @JsonProperty(required = true)
        List<EnrichmentJoin> enrichmentJoins,

        @JsonPropertyDescription("Derived features required (aggregations, time-since, ratios). Empty list if none.")
        @JsonProperty(required = true)
        List<DerivedFeature> derivedFeatures,

        @JsonPropertyDescription("""
            Unmeasured variables where a derived feature computation failed
            entirely. Empty list if none.""")
        @JsonProperty(required = true)
        List<UnmeasuredVariable> unmeasuredVariables,

        @JsonPropertyDescription("Empirical evidence block supporting the hypothesis.")
        @JsonProperty(required = true)
        Evidence evidence,

        @JsonPropertyDescription("""
            Domain-known ordering for categorical treatments. Null when the
            treatment is continuous or no domain ordering exists.""")
        @Nullable DomainRanking domainRanking,

        @JsonPropertyDescription("""
            Measurement metadata fields flagged for residual diagnostic
            checking. Empty list if none.""")
        @JsonProperty(required = true)
        List<String> measurementMetadata,

        @JsonPropertyDescription("""
            Structured execution-time caveats: direction divergence, mandatory
            multivariate control, model-derived threshold, identification
            strategy, rare-data sensitivity, or model sensitivity. Null when
            no caveats apply.""")
        @Nullable List<ExecutionCaveat> executionCaveats
    ) {

        public static Hypothesis forTitle(String title) {
            return new Hypothesis(
                title, "", "", "", null, null, "", "",
                List.of(), List.of(),
                new AnchorGrounding("", List.of(), List.of(), List.of()),
                List.of(), "", List.of(), List.of(), List.of(),
                new Evidence("", "", null, null, null, null, null, null, null, "", null),
                null, List.of(), null
            );
        }
    }

    @JsonClassDescription("""
        Structured execution-time caveat: a methodological constraint that
        must survive into estimation (direction divergence, mandatory
        multivariate control, model-derived threshold, etc.).""")
    public record ExecutionCaveat(

        @JsonPropertyDescription("""
            Caveat category. One of:
            DIRECTION_DIVERGENCE (raw-data direction disagrees with the
            saturated-SS direction) |
            MANDATORY_MULTIVARIATE_CONTROL (single-variable or bivariate
            stratification cannot reproduce the effect; a minimum set of
            control variables is required) |
            MODEL_DERIVED_THRESHOLD (the threshold value comes from the
            model, not from raw stratification) |
            IDENTIFICATION_STRATEGY (a specific conditioning layer is
            required for the claim to hold) |
            RARE_DATA_SENSITIVITY (finding sits on a small subsample) |
            MODEL_SENSITIVITY (the functional form of the estimator
            materially changes the result).""")
        @JsonProperty(required = true)
        String category,

        @JsonPropertyDescription("""
            Description of the caveat: what the issue is and what breaks if
            it is ignored.""")
        @JsonProperty(required = true)
        String description,

        @JsonPropertyDescription("""
            Concrete action to take: which control variables to include,
            which estimation variant to run, which stratification to
            enforce.""")
        @JsonProperty(required = true)
        String guidance
    ) {}

    @JsonClassDescription("Anchor assignment and the iteration chain of stability-selection runs.")
    public record AnchorGrounding(

        @JsonPropertyDescription("The anchor entity assigned to the generator.")
        @JsonProperty(required = true)
        String anchorEntity,

        @JsonPropertyDescription("Reachable enrichment entities listed in the anchor assignment.")
        @JsonProperty(required = true)
        List<String> reachableEnrichment,

        @JsonPropertyDescription("""
            Full chain of stability-selection runs with the saturate/flip
            decisions and the features that surfaced at each step.""")
        @JsonProperty(required = true)
        List<IterationStep> iterationChain,

        @JsonPropertyDescription("""
            Mechanisms saturated during iteration, each with the feature
            list and the reason (exogenous | anchor-external | mediator |
            proxy).""")
        @JsonProperty(required = true)
        List<SaturatedMechanism> saturatedMechanisms
    ) {}

    @JsonClassDescription("One step in the iterative stability-selection decomposition.")
    public record IterationStep(

        @JsonPropertyDescription("Run identifier, e.g. 'run_1', 'run_2a', 'run_2b'.")
        @JsonProperty(required = true)
        String runId,

        @JsonPropertyDescription("Target variable of this SS run. Typically the outcome; the mediator when FLIP_TARGET.")
        @JsonProperty(required = true)
        String target,

        @JsonPropertyDescription("Columns included in controlFeatures for this run.")
        @JsonProperty(required = true)
        List<String> saturated,

        @JsonPropertyDescription("Top feature surfaced by this run.")
        @JsonProperty(required = true)
        String topFeature,

        @JsonPropertyDescription("""
            Classification of the top feature. One of:
            A (near-outcome mediator) |
            B (exogenous / uncontrollable) |
            C (anchor-external actionable) |
            D (anchor-internal actionable).""")
        @JsonProperty(required = true)
        String classification,

        @JsonPropertyDescription("Name of the causal mechanism represented by the top feature.")
        @JsonProperty(required = true)
        String mechanism,

        @JsonPropertyDescription("Action taken in response (saturate, flip target, proceed to SHAP, etc.).")
        @JsonProperty(required = true)
        String action
    ) {}

    @JsonClassDescription("Mechanism whose manifestations were saturated together in one iteration.")
    public record SaturatedMechanism(

        @JsonPropertyDescription("Name of the mechanism.")
        @JsonProperty(required = true)
        String mechanism,

        @JsonPropertyDescription("All features belonging to this mechanism that were saturated together.")
        @JsonProperty(required = true)
        List<String> featuresSaturated,

        @JsonPropertyDescription("Reason for saturation: exogenous | anchor-external | mediator | proxy.")
        @JsonProperty(required = true)
        String reason
    ) {}

    @JsonClassDescription("Directed edge in the hypothesis DAG with causal reasoning.")
    public record DagEdge(

        @JsonPropertyDescription("Source node (cause).")
        @JsonProperty(required = true)
        String from,

        @JsonPropertyDescription("Target node (effect).")
        @JsonProperty(required = true)
        String to,

        @JsonPropertyDescription("""
            Causal reasoning explaining WHY the edge direction goes this
            way. Must articulate a mechanism, not cite correlation alone.""")
        @JsonProperty(required = true)
        String reasoning
    ) {}

    @JsonClassDescription("Enrichment join required to materialize a confounder.")
    public record EnrichmentJoin(

        @JsonPropertyDescription("Key column on the main (base) table.")
        @JsonProperty(required = true)
        String mainTableKey,

        @JsonPropertyDescription("Table joined in for enrichment.")
        @JsonProperty(required = true)
        String joinTable,

        @JsonPropertyDescription("Key column on the enrichment table.")
        @JsonProperty(required = true)
        String joinKey,

        @JsonPropertyDescription("Value column pulled from the enrichment table.")
        @JsonProperty(required = true)
        String valueColumn,

        @JsonPropertyDescription("Default value applied to unmatched rows (COALESCE fallback).")
        @Nullable String defaultValue
    ) {}

    @JsonClassDescription("Derived feature required for the hypothesis: aggregation, time-since, ratio, or temporal derivation.")
    public record DerivedFeature(

        @JsonPropertyDescription("Feature name as it appears in the query.")
        @JsonProperty(required = true)
        String name,

        @JsonPropertyDescription("""
            Computation logic as a SQL expression or aggregation description.
            Must be temporally valid (computable before the outcome is
            observed).""")
        @JsonProperty(required = true)
        String computation,

        @JsonPropertyDescription("Source tables involved in the computation.")
        @JsonProperty(required = true)
        List<String> sourceTables,

        @JsonPropertyDescription("Why this derived feature is temporally valid.")
        @JsonProperty(required = true)
        String temporalOrdering
    ) {}

    @JsonClassDescription("A derived feature that could not be computed, documented as a gap.")
    public record UnmeasuredVariable(

        @JsonPropertyDescription("Intended feature name.")
        @JsonProperty(required = true)
        String name,

        @JsonPropertyDescription("What computation was attempted.")
        @JsonProperty(required = true)
        String intendedComputation,

        @JsonPropertyDescription("Why the computation failed.")
        @JsonProperty(required = true)
        String failureReason,

        @JsonPropertyDescription("What causal structure this gap may hide.")
        @JsonProperty(required = true)
        String impact
    ) {}

    @JsonClassDescription("Empirical evidence block supporting the hypothesis")
    public record Evidence(

        @JsonPropertyDescription("""
            Stability-selection consensus tying the score to the iteration run
            that produced it, with per-model-family ranks and selection
            frequencies. Selection frequency is a decimal fraction in [0, 1]
            (fraction of bootstrap iterations in which the feature was
            selected). Rank is an integer position within a run.""")
        @JsonProperty(required = true)
        String stabilityScore,

        @JsonPropertyDescription("SHAP curve form: monotonic | threshold | nonlinear | categorical.")
        @JsonProperty(required = true)
        String shapCurveForm,

        @JsonPropertyDescription("""
            Breakpoint description when shapCurveForm is 'threshold': the
            breakpoint value with its unit, the Muggeo convergence count
            '<N converged>/<N total>', and the IQR across bootstrap fits.
            Null when shapCurveForm is not 'threshold'.""")
        @Nullable String breakpoint,

        @JsonPropertyDescription("""
            Marginal effect size with unit inline: 'pp' for absolute
            percentage points, '%' for percent in [0, 100], 'x' for fold
            change. Null when the hypothesis is a null finding.""")
        @Nullable String effectSizeMarginal,

        @JsonPropertyDescription("""
            Within-group effect size when ecological fallacy applies: the
            effect after stratifying by the ecological-fallacy dimension.
            Must name the grouping variable and the reduction relative to
            the marginal effect. Same unit convention as effectSizeMarginal.
            Null when no ecological-fallacy stratification applies.""")
        @Nullable String effectSizeWithinGroup,

        @JsonPropertyDescription("""
            Per-condition treatment-level orderings. Use this field when the
            effect ordering flips or differs across different conditioning
            strategies (single-variable vs multi-variable conditioning). Each
            entry captures the conditioning variables and the resulting
            ordering. Null when the ordering is unconditional or has only
            one conditioning layer.""")
        @Nullable List<ConditionalOrdering> conditionalOrderings,

        @JsonPropertyDescription("""
            Per-treatment-level raw outcome rates. Format: each entry is
            '<level>=<rate><unit>', separated by commas. Unit suffix '%' or
            'pp'. Null when the treatment is continuous or raw rates are
            not available.""")
        @Nullable String rawRates,

        @JsonPropertyDescription("""
            Sample size per treatment level. Format: each entry is
            '<level>=<integer_count>', separated by commas or slashes.
            Integer row counts, no unit suffix. Null when the treatment is
            continuous.""")
        @Nullable String nPerLevel,

        @JsonPropertyDescription("""
            Scope limitation: populations where the effect holds vs does
            not, direction reversals by subpopulation, boundary conditions.
            Null when the effect is universally applicable.""")
        @Nullable String scopeLimitation,

        @JsonPropertyDescription("""
            Does domain knowledge predict this relationship? Narrative
            assessment tying the empirical finding to known drivers.""")
        @JsonProperty(required = true)
        String domainAlignment,

        @JsonPropertyDescription("""
            Divergence between data and domain expectations: what was
            expected, what was observed, magnitude of the gap. Null when
            the data matches domain expectations.""")
        @Nullable String domainDiscrepancy
    ) {}

    @JsonClassDescription("""
        One per-condition treatment-level ordering. Captures a conditioning
        strategy and the empirical ordering that emerges under it.""")
    public record ConditionalOrdering(

        @JsonPropertyDescription("""
            Conditioning variables held fixed for this ordering, in the order
            applied. Each entry is a column name with an optional stratum
            specifier. A single-entry list is single-variable conditioning;
            a multi-entry list is joint stratification.""")
        @JsonProperty(required = true)
        List<String> conditioning,

        @JsonPropertyDescription("""
            Empirical ordering that emerges under this conditioning, with
            per-level magnitudes and the winning level. Units inline (pp,
            %, x).""")
        @JsonProperty(required = true)
        String ordering,

        @JsonPropertyDescription("""
            Identification-strategy note: which conditioning layer is
            required to produce this ordering, what happens if a variable
            is dropped, and which control variables are needed to reproduce
            the result. Null when no special note applies.""")
        @Nullable String identificationNote
    ) {}

    @JsonClassDescription("Domain-known ordering of a categorical treatment or modifier.")
    public record DomainRanking(

        @JsonPropertyDescription("Categories ordered weakest effect to strongest effect.")
        @JsonProperty(required = true)
        List<String> ranking,

        @JsonPropertyDescription("Expected ratio from the literature, e.g. 'fastest/slowest = 3x'.")
        @Nullable String expectedRatio,

        @JsonPropertyDescription("Literature reference that grounds the ordering.")
        @JsonProperty(required = true)
        String source
    ) {}

    @JsonClassDescription("""
        Seed attribute that showed no signal in stability selection after
        saturation, with an absence hypothesis and recommended follow-up.""")
    public record BelowDetectionThreshold(

        @JsonPropertyDescription("Anchor seed attribute that showed no SS signal.")
        @JsonProperty(required = true)
        String feature,

        @JsonPropertyDescription("Entity the feature belongs to.")
        @JsonProperty(required = true)
        String anchorEntity,

        @JsonPropertyDescription("""
            Rank of the feature in the final iteration, 1 = highest rank.
            Null when the feature did not appear in the final ranking at
            all.""")
        @Nullable Integer ssRankAfterSaturation,

        @JsonPropertyDescription("""
            Selection frequency in the final iteration: fraction of bootstrap
            runs in which the feature was selected. Decimal fraction in
            [0, 1]. 0.0 means never selected.""")
        @Nullable Double selectionFrequency,

        @JsonPropertyDescription("What domain knowledge says about this variable.")
        @JsonProperty(required = true)
        String domainExpectation,

        @JsonPropertyDescription("""
            Most plausible explanation for the absence of signal. One of:
            confirmed_null | screening | confounding | survivorship |
            conditional_effect | base_rate_dilution | measurement_resolution
            | operational_replacement. Composite values are allowed when
            more than one explanation applies (join with ' + ').""")
        @JsonProperty(required = true)
        String absenceHypothesis,

        @JsonPropertyDescription("Specific reasoning for why the signal is absent in this dataset.")
        @JsonProperty(required = true)
        String absenceReasoning,

        @JsonPropertyDescription("""
            Per-subgroup exceptions to the population-level null. When the
            variable is globally null but specific subgroups retain a
            material signal, list each subgroup with its slope / ratio /
            absolute magnitude. Null when the null is robust across all
            subgroups.""")
        @Nullable List<SubgroupFinding> subgroupFindings,

        @JsonPropertyDescription("Recommended follow-up analysis that might surface the effect.")
        @JsonProperty(required = true)
        RecommendedDownstream recommendedDownstream
    ) {}

    @JsonClassDescription("""
        Subgroup-level exception to a population-level null. Captures the
        grouping variable, the subgroup label, and the effect magnitude
        within that subgroup.""")
    public record SubgroupFinding(

        @JsonPropertyDescription("""
            Grouping dimension the subgroup is defined over (the column used
            to stratify, e.g. a model/cohort column, a region, or a temporal
            bin).""")
        @JsonProperty(required = true)
        String groupingDimension,

        @JsonPropertyDescription("Subgroup label within the grouping dimension.")
        @JsonProperty(required = true)
        String subgroup,

        @JsonPropertyDescription("""
            Effect magnitude within this subgroup, with unit inline. Include
            slope, ratio, and absolute difference where available.""")
        @JsonProperty(required = true)
        String magnitude,

        @JsonPropertyDescription("""
            Sample size for this subgroup. Integer row count. Null when the
            subgroup population count is not known.""")
        @Nullable Long n,

        @JsonPropertyDescription("""
            Short narrative interpreting what this subgroup's signal means:
            why the subgroup surfaces the effect while the population does
            not.""")
        @JsonProperty(required = true)
        String interpretation
    ) {}

    @JsonClassDescription("Recommended follow-up analysis for a below-detection-threshold feature.")
    public record RecommendedDownstream(

        @JsonPropertyDescription("""
            Analysis-type token. One of: GRF_heterogeneity |
            stratified_estimation | include_retired_units | MONITOR_ONLY |
            threshold_search | interaction_test | subpopulation_test | other
            specific method name. MONITOR_ONLY when the null is robust and
            no further analysis is warranted.""")
        @JsonProperty(required = true)
        String analysisType,

        @JsonPropertyDescription("""
            Variables that plausibly moderate the effect. Empty list when
            analysisType is MONITOR_ONLY or no modifiers apply.""")
        @JsonProperty(required = true)
        List<String> proposedModifiers,

        @JsonPropertyDescription("Why this specific analysis might surface the effect.")
        @JsonProperty(required = true)
        String rationale
    ) {}

    @JsonClassDescription("""
        Divergence between a domain-established driver and the empirical
        finding. Can take several forms: the expected signal is absent,
        the observed signal is reversed, the expected ranking is wrong, or
        the magnitude differs from predictions. Form-specific fields
        (absenceHypothesis only applies to the 'expected signal is absent'
        form) are nullable.""")
    public record DomainDiscrepancy(

        @JsonPropertyDescription("What domain knowledge predicts.")
        @JsonProperty(required = true)
        String expectedDriver,

        @JsonPropertyDescription("Domain evidence strength: WELL_ESTABLISHED | DOCUMENTED | SPECULATIVE.")
        @JsonProperty(required = true)
        String domainEvidenceStrength,

        @JsonPropertyDescription("What the data actually shows.")
        @JsonProperty(required = true)
        String empiricalFinding,

        @JsonPropertyDescription("SS rank of the variable across iterations.")
        @Nullable String ssRank,

        @JsonPropertyDescription("""
            Most plausible explanation when the discrepancy takes the form of
            an ABSENT signal (domain expected something, data shows nothing).
            One of: screening | confounding | survivorship | conditional_effect
            | base_rate_dilution | measurement_resolution | screening_by_proxy.
            Null when the discrepancy is not about absence (e.g. the empirical
            ranking is wrong, direction is reversed, or magnitude differs but
            a signal is present).""")
        @Nullable String absenceHypothesis,

        @JsonPropertyDescription("Specific reasoning for this dataset.")
        @JsonProperty(required = true)
        String reasoning,

        @JsonPropertyDescription("Concrete analytical step and expected outcomes under each interpretation.")
        @JsonProperty(required = true)
        ProposedResolution proposedResolution
    ) {}

    @JsonClassDescription("""
        Proposed resolution for a domain discrepancy: a concrete analytical
        step plus the predicted outcomes under the two competing
        interpretations.""")
    public record ProposedResolution(

        @JsonPropertyDescription("Concrete analytical step that tests the discrepancy.")
        @JsonProperty(required = true)
        String method,

        @JsonPropertyDescription("What the test would show if the domain expectation is correct.")
        @JsonProperty(required = true)
        String expectedResultIfReal,

        @JsonPropertyDescription("What the test would show if the domain expectation does not apply.")
        @JsonProperty(required = true)
        String expectedResultIfSpurious
    ) {}

    @JsonClassDescription("Low-frequency, high-impact condition surfaced by the rare-event scan.")
    public record RareEventFinding(

        @JsonPropertyDescription("Column name of the feature containing the rare condition.")
        @JsonProperty(required = true)
        String feature,

        @JsonPropertyDescription("The rare category or condition on the feature.")
        @JsonProperty(required = true)
        String rareLevel,

        @JsonPropertyDescription("Observation count at the rare level. Integer row count, no unit suffix.")
        @JsonProperty(required = true)
        long n,

        @JsonPropertyDescription("""
            Fraction of total rows at the rare level, computed as
            n / total_rows. Decimal fraction in [0, 1]. 0.00045 means
            0.045% of rows.""")
        @JsonProperty(required = true)
        double pctOfTotal,

        @JsonPropertyDescription("""
            Outcome rate within the rare-level subpopulation. Decimal
            fraction in [0, 1]. 0.793 means 79.3% positive outcome.""")
        @JsonProperty(required = true)
        double outcomeRateAtRare,

        @JsonPropertyDescription("""
            Overall baseline outcome rate across all rows. Decimal fraction
            in [0, 1]. 0.065 means 6.5% positive outcome overall.""")
        @JsonProperty(required = true)
        double outcomeRateBaseline,

        @JsonPropertyDescription("""
            Rare-level outcome rate divided by baseline outcome rate.
            Dimensionless multiplier, >= 0. 12.2 means '12.2x the baseline
            rate'. Never a fraction or percentage.""")
        @JsonProperty(required = true)
        double rateRatio,

        @JsonPropertyDescription("""
            True if the feature belongs to an anchor source entity (anchor
            itself or a reachable enrichment entity). False otherwise.""")
        @JsonProperty(required = true)
        boolean anchorInternal,

        @JsonPropertyDescription("Physical reasoning for why the condition causes extreme outcomes.")
        @JsonProperty(required = true)
        String mechanism,

        @JsonPropertyDescription("""
            Tautology check: whether the finding is trivially true by
            definition or reflects real causation, with the justification.""")
        @JsonProperty(required = true)
        String tautologyCheck,

        @JsonPropertyDescription("""
            Adjacent or moderating variable checks and their findings. Each
            entry records one conditional subset and what it showed.""")
        @JsonProperty(required = true)
        List<ConditionalContext> conditionalContext,

        @JsonPropertyDescription("Operational intervention this finding suggests.")
        @JsonProperty(required = true)
        String prescriptiveImplication,

        @JsonPropertyDescription("""
            One of: DIRECT_PRESCRIPTIVE | VERIFY_AND_PRESCRIBE |
            MONITOR_ONLY. DIRECT_PRESCRIPTIVE = finding strong enough to
            act on immediately; VERIFY_AND_PRESCRIBE = additional
            verification required before acting; MONITOR_ONLY = collect more
            data before deciding.""")
        @JsonProperty(required = true)
        String recommendedAction
    ) {}

    @JsonClassDescription("One conditional check on a rare-event finding.")
    public record ConditionalContext(

        @JsonPropertyDescription("Adjacent or moderating variable that was checked.")
        @JsonProperty(required = true)
        String condition,

        @JsonPropertyDescription("What the conditional analysis showed.")
        @JsonProperty(required = true)
        String finding
    ) {}

    @JsonClassDescription("""
        Suspected interaction between anchor-internal variables that appear
        to matter jointly in a way neither captures alone.""")
    public record InteractionCandidate(

        @JsonPropertyDescription("Component column names participating in the interaction.")
        @JsonProperty(required = true)
        List<String> components,

        @JsonPropertyDescription("Identifiers of the component hypotheses, e.g. ['H1', 'H2'].")
        @JsonProperty(required = true)
        List<String> componentHypotheses,

        @JsonPropertyDescription("""
            Evidence of joint signal: SHAP effect modifier, sign flip
            across strata, co-presence requirement, etc.""")
        @JsonProperty(required = true)
        String jointSignalEvidence,

        @JsonPropertyDescription("""
            Expected interaction form: synergistic | antagonistic |
            regime_change | co_presence_required.""")
        @JsonProperty(required = true)
        String expectedInteractionForm,

        @JsonPropertyDescription("Mechanism reasoning for why the components interact rather than add.")
        @JsonProperty(required = true)
        String reasoning
    ) {}
}
