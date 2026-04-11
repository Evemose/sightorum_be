package com.rorm.ai.swarm.dto;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.jspecify.annotations.Nullable;

import java.util.List;

@JsonClassDescription("""
    'Outside view' of a research domain: base rates, known causal drivers,
    typical effect sizes, common pitfalls, and metric definitions from the
    literature and industry benchmarks. Neutral - does not advocate any
    hypothesis.
    
    Numeric conventions:
    - Every numeric quantity carried in a String field (typical range,
      effect size) MUST include its unit inline. Acceptable units: 'pp'
      for absolute percentage points, '%' for percent in [0, 100], 'x'
      for dimensionless fold change, plus any physical unit the metric
      needs.
    - All quantities are narrative strings so ranges and units stay
      together.""")
public record DomainResearchDTO(

    @JsonPropertyDescription("""
        Domain identification: name, sub-domain, confidence (HIGH/MEDIUM/LOW), and a
        brief 2-3 sentence description establishing the reference class.""")
    @JsonProperty(required = true)
    DomainIdentification domainIdentification,

    @JsonPropertyDescription("""
        Reference class baselines per key metric: standard definition, typical range
        with source and recency, and how observed values compare where known.""")
    @JsonProperty(required = true)
    List<ReferenceClassBaseline> referenceClassBaselines,

    @JsonPropertyDescription("""
        Known causal drivers from the domain literature, each with direction, mechanism,
        evidence strength, typical effect size range, and source. Includes any confound
        warnings (e.g. reverse causation risks).""")
    @JsonProperty(required = true)
    List<CausalDriver> knownCausalDrivers,

    @JsonPropertyDescription("""
        Common pitfalls including the dominant confound structure of the domain,
        measurement biases, and documented Simpson's paradox instances. Each with
        description, detection/control strategy, and severity.""")
    @JsonProperty(required = true)
    List<Pitfall> commonPitfalls,

    @JsonPropertyDescription("""
        Domain-specific metric definitions: standard industry definition(s), measurement
        methodology, and known ambiguities in operationalization.""")
    @JsonProperty(required = true)
    List<MetricDefinition> metricDefinitions,

    @JsonPropertyDescription("""
        Geographic-region characteristics relevant to the domain (if geospatial anchors
        were provided). Null if not applicable.""")
    @Nullable String geospatialContext,

    @JsonPropertyDescription("""
        Reference class limitations: how well the reference class matches this specific
        dataset, and sparse research areas where forecasting is unreliable.""")
    @JsonProperty(required = true)
    List<String> referenceClassLimitations
) {

    @JsonClassDescription("Identification of the domain being researched")
    public record DomainIdentification(

        @JsonPropertyDescription("Primary domain name (e.g. 'SaaS customer retention')")
        @JsonProperty(required = true)
        String domain,

        @JsonPropertyDescription("Sub-domain if applicable (e.g. 'B2B subscription services')")
        @Nullable String subDomain,

        @JsonPropertyDescription("Confidence that the reference class applies: HIGH, MEDIUM, or LOW")
        @JsonProperty(required = true)
        String confidence,

        @JsonPropertyDescription("Brief 2-3 sentence description of why this is the domain")
        @JsonProperty(required = true)
        String description
    ) {}

    @JsonClassDescription("Baseline value range for a single metric, cross-referenced against published sources")
    public record ReferenceClassBaseline(

        @JsonPropertyDescription("Name of the metric as used in the literature.")
        @JsonProperty(required = true)
        String metric,

        @JsonPropertyDescription("""
            Standard definition of the metric in this domain, including the unit
            (physical unit for concentrations, 'percent' for proportions, 'count'
            for counts, etc.). Without a unit the definition is incomplete.""")
        @JsonProperty(required = true)
        String standardDefinition,

        @JsonPropertyDescription("""
            Typical value range as a RANGE with the unit attached
            (low-high form, never a point estimate). Must use the same
            unit as the definition so the range is comparable to observed
            values.""")
        @JsonProperty(required = true)
        String typicalRange,

        @JsonPropertyDescription("Source of the baseline (industry report, regulator, peer-reviewed study)")
        @JsonProperty(required = true)
        String source,

        @JsonPropertyDescription("Recency of the source (year or publication date); older baselines may not apply.")
        @Nullable String recency,

        @JsonPropertyDescription("""
            Interpretation calibrating the baseline for this specific dataset: how
            observed values compare to the range, what counts as good vs bad in
            this reference class, and where the dataset sits. Null if the user did
            not supply an observed value to calibrate against.""")
        @Nullable String interpretation
    ) {}

    @JsonClassDescription("A causal driver that the domain literature treats as established or documented")
    public record CausalDriver(

        @JsonPropertyDescription("Name of the driving factor as used in the literature.")
        @JsonProperty(required = true)
        String factor,

        @JsonPropertyDescription("""
            Mechanism: the physical/operational/behavioural reason the factor
            drives the outcome. Must describe the causal pathway, not just the
            correlation.""")
        @JsonProperty(required = true)
        String mechanism,

        @JsonPropertyDescription("""
            Direction: the sign and shape of the relationship, written as
            '<condition on driver> -> <consequence on outcome>'. Include whether
            the relationship is monotonic or has a regime change.""")
        @JsonProperty(required = true)
        String direction,

        @JsonPropertyDescription("""
            Evidence strength in the literature: WELL_ESTABLISHED | DOCUMENTED |
            DISPUTED | SPECULATIVE.""")
        @JsonProperty(required = true)
        String evidenceStrength,

        @JsonPropertyDescription("""
            Typical effect size as a RANGE with the unit attached (never a point
            estimate). Units must be unambiguous about absolute vs relative vs
            fold: 'pp' for absolute percentage points (e.g. '+3pp to +5pp'),
            '%' for percent change in [0, 100] (e.g. '10%-30% reduction'), 'x'
            for dimensionless fold change (e.g. '2x-5x').""")
        @JsonProperty(required = true)
        String typicalEffectSize,

        @JsonPropertyDescription("""
            Documented nonlinearities, thresholds, diminishing returns, or regime
            changes applying to this driver. Include the threshold value with
            unit where known. Null if the driver is approximately linear over its
            operating range.""")
        @Nullable String nonlinearity,

        @JsonPropertyDescription("Key source for the driver (paper, industry report, regulator).")
        @Nullable String source,

        @JsonPropertyDescription("""
            Optional confound warning (reverse causation risks, shared upstream
            causes, compositional issues) relevant when interpreting this driver
            empirically. Null if no special warning applies.""")
        @Nullable String confoundWarning
    ) {}

    @JsonClassDescription("A common analytical pitfall in this domain with detection/control advice")
    public record Pitfall(

        @JsonPropertyDescription("Description of the pitfall and how it manifests")
        @JsonProperty(required = true)
        String description,

        @JsonPropertyDescription("How to detect or control for the pitfall")
        @JsonProperty(required = true)
        String howToDetect,

        @JsonPropertyDescription("Severity: LOW, MEDIUM, or HIGH")
        @JsonProperty(required = true)
        String severity
    ) {}

    @JsonClassDescription("Standard definition(s) of a metric, with measurement method and known ambiguities")
    public record MetricDefinition(

        @JsonPropertyDescription("Metric name")
        @JsonProperty(required = true)
        String metric,

        @JsonPropertyDescription("""
            Standard industry definitions. Multiple if the domain uses more than one
            operationalization (e.g. logo churn vs revenue churn vs 30/60/90-day inactivity).""")
        @JsonProperty(required = true)
        List<String> standardDefinitions,

        @JsonPropertyDescription("Measurement methodology where specified in the literature")
        @Nullable String measurementMethod,

        @JsonPropertyDescription("Known ambiguities or edge cases in how this metric is operationalized")
        @Nullable String knownAmbiguities
    ) {}
}
