package com.rorm.ml.dto.pipelinespec;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.jspecify.annotations.Nullable;

import java.util.List;

@JsonNaming(SnakeCaseStrategy.class)
@JsonClassDescription("""
    Sensitivity analyses: per-confounder drop and add tests, optional
    threshold variants for binary-threshold treatments, and
    alternative-model comparisons.""")
public record SensitivityConfig(

    @JsonPropertyDescription("""
        Drop-one-confounder sensitivity tests, one per W variable. Each
        entry re-estimates with the column removed from W and compares
        against the full estimate.""")
    @JsonProperty(required = true)
    List<ConfounderDrop> confounderDrops,

    @JsonPropertyDescription("""
        Add-one-confounder sensitivity tests: plausible omitted
        confounders that are re-included to check whether their addition
        changes the estimate.""")
    @JsonProperty(required = true)
    List<ConfounderAdd> confounderAdds,

    @JsonPropertyDescription("""
        Candidate thresholds to sweep for BINARY_THRESHOLD treatments.
        Null when the treatment is not binary-thresholded.""")
    @Nullable List<ThresholdVariant> thresholdVariants,

    @JsonPropertyDescription("""
        Alternative-model variants that re-estimate a primary variant
        under a different nuisance model family (e.g. a NonParamDML
        counterpart to a LinearDML primary).""")
    @JsonProperty(required = true)
    List<ModelVariant> modelVariants
) {

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonClassDescription("One confounder-drop entry: the W variable to drop and its flag threshold.")
    public record ConfounderDrop(

        @JsonPropertyDescription("W variable to drop. Must be in the query SELECT and not stripped.")
        @JsonProperty(required = true)
        String column,

        @JsonPropertyDescription("""
            Relative change in ATE magnitude above which the drop is
            flagged. Dimensionless decimal fraction - 0.20 means 'flag
            when removing this variable changes the ATE magnitude by more
            than 20% of its current value'. Must be > 0.""")
        @JsonProperty(required = true)
        double deviationThresholdPct
    ) {}

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonClassDescription("One confounder-add entry: a plausible omitted confounder with a short reasoning.")
    public record ConfounderAdd(

        @JsonPropertyDescription("""
            Column to add back to W. Must be in the query SELECT and not
            stripped. Reference only columns the query actually returns.""")
        @JsonProperty(required = true)
        String column,

        @JsonPropertyDescription("Short reasoning explaining why this variable is a candidate for re-inclusion.")
        @JsonProperty(required = true)
        String reasoning
    ) {}

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonClassDescription("Candidate binary threshold to sweep for BINARY_THRESHOLD treatments.")
    public record ThresholdVariant(

        @JsonPropertyDescription("Threshold value on the treatment's numeric scale.")
        @JsonProperty(required = true)
        double threshold,

        @JsonPropertyDescription("Expected count of rows with treatment > threshold.")
        @JsonProperty(value = "expected_n_treated", required = true)
        @JsonAlias("expected_ntreated")
        int expectedNTreated,

        @JsonPropertyDescription("Expected count of rows with treatment <= threshold.")
        @JsonProperty(value = "expected_n_control", required = true)
        @JsonAlias("expected_ncontrol")
        int expectedNControl
    ) {}

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonClassDescription("""
        Alternative-model variant: re-estimate a primary variant using a
        different nuisance-model family to test functional-form
        assumptions.""")
    public record ModelVariant(

        @JsonPropertyDescription("Id of the primary estimation variant to re-estimate.")
        @JsonProperty(required = true)
        String primaryVariantId,

        @JsonPropertyDescription("Alternative nuisance model type token, e.g. 'NonParamDML'.")
        @JsonProperty(required = true)
        String alternativeModelType
    ) {}
}
