package com.rorm.ml.dto.pipelinespec;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

@JsonNaming(SnakeCaseStrategy.class)
@JsonClassDescription("""
    Externalization block: compares empirical findings against external
    sources - domain-predicted orderings and allocation-bias checks
    across deployment groupings.""")
public record ExternalizationConfig(

    @JsonPropertyDescription("""
        Domain-predicted orderings to compare against empirical effects.
        Each entry carries a tier-notation ordering plus a source and
        expected concordance. Empty list when no domain ranking is
        available.""")
    @JsonProperty(required = true)
    List<DomainRanking> domainRankings,

    @JsonPropertyDescription("""
        Allocation-bias checks over deployment groupings. Each entry is
        flagged when the HHI concentration exceeds its threshold. Empty
        list when no allocation-bias check applies.""")
    @JsonProperty(required = true)
    List<AllocationBias> allocationBias
) {

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonClassDescription("""
        Domain-predicted severity ordering in tier notation. Tiers are
        parenthesized groups separated by '>'; elements inside a tier
        are approximately equal; cross-tier pairs assert the left tier
        has larger effect magnitude than the right tier. For CONTINUOUS
        treatments, tier elements are numeric threshold values; for
        CATEGORICAL treatments, tier elements are slice keys in the
        form 'column=value'.""")
    public record DomainRanking(

        @JsonPropertyDescription("""
            Ordering expression in tier notation, e.g. '(A) > (B, C) >
            (D)'. Unwinds into pairwise assertions that are then checked
            against the empirical ordering.""")
        @JsonProperty(required = true)
        String ordering,

        @JsonPropertyDescription("Domain-knowledge reference that grounds the ordering.")
        @JsonProperty(required = true)
        String source,

        @JsonPropertyDescription("""
            Subpopulation or scope restriction when the ordering only
            applies to a subset of the data. Empty string when the
            ordering applies to the full sample.""")
        @JsonProperty(required = true)
        String scope,

        @JsonPropertyDescription("""
            Minimum fraction of pairwise assertions that must be satisfied
            for the ordering to be considered confirmed. Decimal fraction
            in [0, 1]. Calibration: 0.70-0.90 for well-established
            orderings, 0.40-0.60 for documented but divergent orderings,
            0.20-0.30 for exploratory orderings.""")
        @JsonProperty(required = true)
        double expectedConcordance
    ) {}

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonClassDescription("""
        Allocation-bias check: computes the Herfindahl-Hirschman index
        (HHI) of treatment levels across a grouping column and flags
        non-uniform deployment.""")
    public record AllocationBias(

        @JsonPropertyDescription("Treatment column whose allocation is being checked.")
        @JsonProperty(required = true)
        String treatmentColumn,

        @JsonPropertyDescription("Grouping column over which the HHI is computed (typically a deployment dimension).")
        @JsonProperty(required = true)
        String groupingColumn,

        @JsonPropertyDescription("""
            HHI concentration threshold above which allocation is flagged
            as non-uniform. Decimal fraction in [0, 1]; 0.10 means 'flag
            when any treatment level concentrates more than 0.10 HHI'.""")
        @JsonProperty(required = true)
        double flagThreshold
    ) {}
}
