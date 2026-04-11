package com.rorm.ml.dto.pipelinespec;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

@JsonNaming(SnakeCaseStrategy.class)
@JsonClassDescription("""
    Residual diagnostic checks: autocorrelation at one or more lag
    structures, field-correlation scan, iterative auto-correction, and
    per-column measurement-metadata correlation checks.""")
public record ResidualChecks(

    @JsonPropertyDescription("""
        Autocorrelation checks, one per (temporal column, grain)
        combination. Empty list when no temporal autocorrelation check is
        configured.""")
    @JsonProperty(required = true)
    List<AutocorrelationCheck> autocorrelation,

    @JsonPropertyDescription("""
        Field-correlation check: scans residuals against non-W columns
        plus W columns to detect nonlinear confounding missed by a linear
        model.""")
    @JsonProperty(required = true)
    FieldCorrelationCheck fieldCorrelation,

    @JsonPropertyDescription("""
        Iterative auto-correction loop: re-fits the estimator while the
        CI width still moves by more than stopCriterionCiPct between
        iterations, bounded by maxIterations.""")
    @JsonProperty(required = true)
    AutoCorrectionConfig autoCorrection,

    @JsonPropertyDescription("""
        Measurement-metadata correlation checks, one per metadata column.
        Flags residual correlation with sensor calibration age, logger
        id, etc. Empty list when no metadata columns are present.""")
    @JsonProperty(required = true)
    List<MetadataCorrelation> metadataCorrelation
) {

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonClassDescription("One autocorrelation check at a single temporal grain with a list of lags.")
    public record AutocorrelationCheck(

        @JsonPropertyDescription("Temporal column. Must be in the query SELECT and not stripped.")
        @JsonProperty(required = true)
        String temporalColumn,

        @JsonPropertyDescription("Temporal grain (valid pandas period frequency, e.g. 'M' or 'W').")
        @JsonProperty(required = true)
        String grain,

        @JsonPropertyDescription("""
            Lag offsets to check. Non-empty, strictly positive integers,
            each less than expectedRowCount.""")
        @JsonProperty(required = true)
        List<Integer> lags,

        @JsonPropertyDescription("""
            Autocorrelation magnitude above which the check flags.
            Dimensionless correlation magnitude in (0, 1]. Must be > 0.""")
        @JsonProperty(required = true)
        double threshold
    ) {}

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonClassDescription("""
        Field-correlation check: per-column residual correlation threshold
        applied to the listed check columns.""")
    public record FieldCorrelationCheck(

        @JsonPropertyDescription("""
            Correlation magnitude threshold in (0, 1]. Typically 15x-20x
            the spurious-correlation noise floor 1 / sqrt(expectedRowCount).""")
        @JsonProperty(required = true)
        double threshold,

        @JsonPropertyDescription("""
            Columns to check: non-W columns plus W columns (to catch
            nonlinear confounding the linear model missed). All must be
            in the query SELECT and not stripped.""")
        @JsonProperty(required = true)
        List<String> checkColumns
    ) {}

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonClassDescription("""
        Iterative auto-correction settings. Stops when the CI width
        changes by less than stopCriterionCiPct between iterations or
        when maxIterations is reached.""")
    public record AutoCorrectionConfig(

        @JsonPropertyDescription("Maximum number of auto-correction iterations. Must be > 0.")
        @JsonProperty(required = true)
        int maxIterations,

        @JsonPropertyDescription("""
            Stop criterion on CI width: relative change in CI width
            between consecutive iterations below which iteration stops.
            Dimensionless decimal fraction in (0, 1]; 0.05 means 'stop
            when the CI width changes by less than 5%'.""")
        @JsonProperty(required = true)
        double stopCriterionCiPct
    ) {}

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonClassDescription("Measurement-metadata correlation check for a single metadata column.")
    public record MetadataCorrelation(

        @JsonPropertyDescription("Metadata column to check. Must be in the query SELECT and not stripped.")
        @JsonProperty(required = true)
        String column,

        @JsonPropertyDescription("""
            Correlation magnitude threshold in (0, 1] above which the
            check flags. Must be > 0.""")
        @JsonProperty(required = true)
        double threshold,

        @JsonPropertyDescription("Short alert label describing the type of issue this check surfaces.")
        @JsonProperty(required = true)
        String alertType
    ) {}
}
