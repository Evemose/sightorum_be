package com.rorm.ml.dto.pipelinespec;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(SnakeCaseStrategy.class)
@JsonClassDescription("""
    Quality gate thresholds: nuisance-model fit, sanity check on effect
    direction and magnitude, and placebo-test ratio.""")
public record QualityGates(

    @JsonPropertyDescription("Nuisance-model R-squared thresholds (abort and flag) for outcome and treatment models.")
    @JsonProperty(required = true)
    NuisanceR2Gates nuisanceR2,

    @JsonPropertyDescription("Sanity check on expected direction and effect magnitude.")
    @JsonProperty(required = true)
    SanityGates sanity,

    @JsonPropertyDescription("Placebo test flag ratio.")
    @JsonProperty(required = true)
    PlaceboGates placebo
) {

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonClassDescription("""
        Nuisance-model R-squared thresholds. All four abort/flag thresholds
        are decimal fractions in [0, 1] (R-squared values, never percent).
        treatmentStructuralMaxR2 records the theoretical maximum R-squared
        for the treatment's distribution as a reference value.""")
    public record NuisanceR2Gates(

        @JsonPropertyDescription("Outcome model R-squared below which the run aborts. Decimal fraction in [0, 1].")
        @JsonProperty(required = true)
        double outcomeAbort,

        @JsonPropertyDescription("Outcome model R-squared below which a warning is raised. Decimal fraction in [0, 1].")
        @JsonProperty(required = true)
        double outcomeFlag,

        @JsonPropertyDescription("Treatment model R-squared below which the run aborts. Decimal fraction in [0, 1].")
        @JsonProperty(required = true)
        double treatmentAbort,

        @JsonPropertyDescription("Treatment model R-squared below which a warning is raised. Decimal fraction in [0, 1].")
        @JsonProperty(required = true)
        double treatmentFlag,

        @JsonPropertyDescription("""
            Theoretical maximum R-squared for the treatment's distribution,
            used as a reference when interpreting the abort/flag thresholds
            relative to the structural ceiling. Decimal fraction in [0, 1].""")
        @JsonProperty(required = true)
        double treatmentStructuralMaxR2
    ) {}

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonClassDescription("""
        Sanity gate on the effect direction and magnitude. expectedDirection
        must be exactly -1 or +1 (never 0). abortMagnitude and flagMagnitude
        are absolute effect thresholds in the same units as the outcome's
        residual - for binary outcomes these are absolute probability
        differences (0.05 means 'five percentage points') stored as decimal
        fractions.""")
    public record SanityGates(

        @JsonPropertyDescription("Expected sign of the ATE: exactly -1 or +1 (the gate rejects 0).")
        @JsonProperty(required = true)
        int expectedDirection,

        @JsonPropertyDescription("""
            Effect magnitude above which the run aborts. Decimal fraction
            on the outcome's scale; for binary outcomes this is an absolute
            probability difference in [0, 1].""")
        @JsonProperty(required = true)
        double abortMagnitude,

        @JsonPropertyDescription("""
            Effect magnitude above which a warning is raised. Same units as
            abortMagnitude; flagMagnitude < abortMagnitude is the expected
            ordering.""")
        @JsonProperty(required = true)
        double flagMagnitude
    ) {}

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonClassDescription("""
        Placebo test gate. flagRatio is the dimensionless ratio of placebo
        ATE magnitude to real ATE magnitude above which the placebo test
        flags a contamination. Decimal fraction in [0, 1] - 0.30 means
        'flag when the placebo ATE is more than 30% of the real ATE'.""")
    public record PlaceboGates(

        @JsonPropertyDescription("Dimensionless ratio |placebo ATE| / |real ATE| above which the placebo check flags.")
        @JsonProperty(required = true)
        double flagRatio
    ) {}
}
