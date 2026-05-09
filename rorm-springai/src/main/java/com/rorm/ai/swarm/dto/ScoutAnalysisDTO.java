package com.rorm.ai.swarm.dto;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.jspecify.annotations.Nullable;

import java.util.List;

@JsonClassDescription("""
    Headless descriptive exploration of a dataset described by the metamodel.
    Surfaces load-bearing patterns, segmentations, divergences, and hazards
    that downstream causal-investigation agents (hypothesis generators,
    judges, advocates, prosecutors) consume as their shared data overview.
    Operates in a CENSUS frame: computed values are facts about the full
    population, not estimates. Does NOT explain why patterns exist, predict
    future values, or make causal claims.
    
    Numeric conventions:
    - Counts (rows, attributes, samples, segments): plain integers, no unit suffix.
    - Rates and proportions: decimal fraction in [0, 1]. A 5% null rate is
      0.05, NOT 5. Percentages from tool output must be divided by 100.
    - Fan-out ratios: dimensionless (child rows / parent rows).
    - Dates: ISO-8601 strings.""")
public record ScoutAnalysisDTO(

    @JsonPropertyDescription("""
        2-3 sentence summary capturing the most load-bearing pattern across
        the exploration: what the dataset is, what the headline measure
        looks like, and the single biggest finding (or 'no divergence
        detected' when everything is uniform). Numbers stay in the insights
        below; the survey states the structural takeaway.""")
    @JsonProperty(required = true)
    String headlineSurvey,

    @JsonPropertyDescription("""
        Exploration findings, one per archetype call. Each insight is a
        single descriptive fact about the data, never a causal claim. Order
        is significant — most load-bearing first.""")
    @JsonProperty(required = true)
    List<DataInsight> insights,

    @JsonPropertyDescription("""
        Data hazards downstream agents must know about: intrinsic columns
        (leakage), fan-out risks on 1:N relationships, data quality issues
        with quantified impact. Severity reflects whether the hazard blocks
        analysis or is informational.""")
    @JsonProperty(required = true)
    List<DataHazard> hazards,

    @JsonPropertyDescription("""
        Compact ~200-token landscape overview, one line per entity in the
        metamodel: entity_name(row_count, attribute_count, [flags]) with
        key fields, target annotations, FK relationship graph, and
        geospatial hierarchy when present. The bird's-eye orientation
        downstream agents read first.""")
    @JsonProperty(required = true)
    String schemaSummary,

    @JsonPropertyDescription("""
        Proposed anchor entities for downstream hypothesis fanout. Each
        anchor nominates one entity-level perspective the swarm will
        explore in its own parallel generator-compiler-sceptic pipeline.
        Typically 2-4 anchors based on the strongest divergences or
        operational lenses surfaced in the survey; one minimum, more
        allowed only when the survey shows multiple distinct
        high-divergence axes that cannot be unified.""")
    @JsonProperty(required = true)
    List<ProposedAnchor> proposedAnchors
) {

    @JsonClassDescription("Single descriptive exploration finding produced by one archetype tool call")
    public record DataInsight(

        @JsonPropertyDescription("Short topic the insight is about (e.g. 'fleet excursion rate', 'container age distribution')")
        @JsonProperty(required = true)
        String topic,

        @JsonPropertyDescription("""
            Archetype tool that produced the insight. One of:
            summaryStatistic | trendSeries | rankedList | compareSides |
            crossTabulation | stratifiedGradient | deploymentDistribution.""")
        @JsonProperty(required = true)
        String archetype,

        @JsonPropertyDescription("""
            One descriptive sentence stating what is in the data. No causal
            language ('drives', 'causes', 'explains', 'due to', 'because').
            No predictions ('expected to', 'on track to'). Co-variation,
            divergence, entanglement are fine.""")
        @JsonProperty(required = true)
        String finding,

        @JsonPropertyDescription("""
            Load-bearing values: the measure, sample sizes, denominators,
            segment counts. Numbers as plain numerals; rates as decimal
            fractions in [0, 1].""")
        @JsonProperty(required = true)
        String supportingMetrics,

        @JsonPropertyDescription("""
            Fired check codes (e.g. C1, R2, T1, T4, K2, K3) with one-clause
            consequence each, window choices when T3-sensitive, frame
            caveats, heterogeneity warnings. 'None fired' when nothing
            fired.""")
        @JsonProperty(required = true)
        String caveats,

        @JsonPropertyDescription("""
            Axes, segments, or windows downstream agents may want to
            investigate further. Anchor points for hypothesis generation,
            not commitments. Empty list when nothing notable surfaced.""")
        @JsonProperty(required = true)
        List<String> explorationHandles
    ) {}

    @JsonClassDescription("A data hazard downstream agents must account for, with quantified impact and severity")
    public record DataHazard(

        @JsonPropertyDescription("""
            Hazard type, one of the canonical tokens: intrinsic_column |
            high_null_rate | small_table | duplicate_rows | orphaned_fk |
            temporal_gap | referential_integrity | dead_field |
            impossible_value | parallel_tables | sparse_coverage |
            fanout_risk.""")
        @JsonProperty(required = true)
        String kind,

        @JsonPropertyDescription("Entity where the hazard occurs (or the parent entity for fanout_risk)")
        @JsonProperty(required = true)
        String entity,

        @JsonPropertyDescription("Field where the hazard occurs; null for entity-level hazards (small_table) or relationship-level (fanout_risk)")
        @Nullable String field,

        @JsonPropertyDescription("Severity of the hazard: LOW | MEDIUM | HIGH")
        @JsonProperty(required = true)
        String severity,

        @JsonPropertyDescription("""
            What was found, with quantified impact: counts, rates, ratios.
            Rates as decimal fractions in [0, 1]. For fanout_risk, include
            both the child entity name and the fan-out ratio.""")
        @JsonProperty(required = true)
        String description,

        @JsonPropertyDescription("True when the hazard blocks analysis; false when it is informational but non-blocking")
        @JsonProperty(required = true)
        boolean blocksAnalysis
    ) {}
}
