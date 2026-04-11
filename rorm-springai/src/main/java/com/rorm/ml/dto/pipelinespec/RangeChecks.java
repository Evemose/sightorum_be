package com.rorm.ml.dto.pipelinespec;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.jspecify.annotations.Nullable;

import java.util.List;

@JsonNaming(SnakeCaseStrategy.class)
@JsonClassDescription("""
    Range checks: VIF collinearity screening, per-binary-variant
    propensity overlap, and near-degeneracy variance notes.""")
public record RangeChecks(

    @JsonPropertyDescription("VIF collinearity config for numeric W variables with drop decisions on high-VIF pairs.")
    @JsonProperty(required = true)
    VifConfig vif,

    @JsonPropertyDescription("""
        Overlap (propensity) check, one per binary estimation variant.
        Empty list when the spec has no binary variants.""")
    @JsonProperty(required = true)
    List<OverlapCheck> overlap,

    @JsonPropertyDescription("""
        Variance notes for columns flagged as near-degenerate or
        otherwise structurally constrained.""")
    @JsonProperty(required = true)
    List<VarianceCheck> variance
) {

    @JsonClassDescription("""
        Response strategy when a binary variant fails the propensity
        overlap check. TRIM restricts the sample to the overlap region
        defined by trimBounds and re-estimates on the survivors. MATCH
        switches to nearest-neighbour matching within the overlap region.
        LATE reinterprets the estimate as a local average treatment
        effect on the overlap region and reports it with a narrower
        scope.""")
    public enum OverlapStrategy {
        TRIM, MATCH, LATE
    }

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonClassDescription("""
        VIF collinearity check. threshold sets the VIF ceiling above
        which a pair is flagged. dropPairs records explicit keep-vs-drop
        decisions for high-VIF pairs.""")
    public record VifConfig(

        @JsonPropertyDescription("""
            VIF magnitude above which a pair is flagged. Dimensionless.
            Typical values 10 to 50 depending on tolerance for
            collinearity.""")
        @JsonProperty(required = true)
        double threshold,

        @JsonPropertyDescription("""
            Explicit keep/drop decisions for high-VIF pairs, one entry
            per pair. Empty list when no pair exceeds the threshold.""")
        @JsonProperty(required = true)
        List<VifDropPair> dropPairs
    ) {}

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonClassDescription("Keep/drop decision for a single high-VIF pair.")
    public record VifDropPair(

        @JsonPropertyDescription("Column to retain in W.")
        @JsonProperty(required = true)
        String keep,

        @JsonPropertyDescription("Column to drop from W.")
        @JsonProperty(required = true)
        String drop,

        @JsonPropertyDescription("Short reasoning for the keep/drop decision.")
        @JsonProperty(required = true)
        String reasoning
    ) {}

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonClassDescription("""
        Propensity overlap check for one binary estimation variant. If
        overlap is insufficient, responseStrategy selects how to proceed:
        TRIM restricts to the overlap region, MATCH switches to
        nearest-neighbour matching, LATE reinterprets the estimate as a
        local effect.""")
    public record OverlapCheck(

        @JsonPropertyDescription("Id of the estimation variant being overlap-checked.")
        @JsonProperty(required = true)
        String variantId,

        @JsonPropertyDescription("""
            Propensity overlap threshold in (0, 1]. Typical values 0.01
            or 0.05.""")
        @JsonProperty(required = true)
        double threshold,

        @JsonPropertyDescription("Response strategy when positivity is violated: TRIM | MATCH | LATE.")
        @JsonProperty(required = true)
        OverlapStrategy responseStrategy,

        @JsonPropertyDescription("""
            Trim bounds [low, high] for the TRIM strategy: exactly two
            values with low < high, both in (0, 1). Required when
            responseStrategy is TRIM, null otherwise.""")
        @Nullable List<Double> trimBounds
    ) {}

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonClassDescription("""
        Variance note for a column flagged as near-degenerate or
        otherwise structurally constrained (rare event, low variance,
        bounded range).""")
    public record VarianceCheck(

        @JsonPropertyDescription("Column being variance-checked.")
        @JsonProperty(required = true)
        String column,

        @JsonPropertyDescription("""
            Narrative note explaining the structural context (rare-event
            prevalence, bounded range, near-zero variance).""")
        @JsonProperty(required = true)
        String structuralNote
    ) {}
}
