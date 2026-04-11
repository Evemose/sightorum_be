package com.rorm.ml.dto;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.rorm.dto.dense.DenseQueryDto;
import com.rorm.ml.dto.pipelinespec.*;
import lombok.Builder;
import org.jspecify.annotations.Nullable;

import java.util.List;

@Builder
@JsonNaming(SnakeCaseStrategy.class)
@JsonClassDescription("""
    Full specification for a causal-verification run on a single hypothesis.
    
    Numeric conventions:
    - dsepThreshold: correlation magnitude in (0, 1], strictly positive.
    - expectedRowCount: integer row count, no unit suffix.
    - All R-squared gates: decimal fraction in [0, 1].
    - Effect-magnitude gates: absolute outcome-scale differences in [0, 1]
      (for binary outcomes these are absolute probability differences -
      0.05 means 'five percentage points').
    - HHI flag thresholds: Herfindahl-Hirschman concentration in [0, 1].""")
public record PipelineSpecRequest(

    @JsonPropertyDescription("Hypothesis identifier, copied verbatim from the source hypothesis.")
    @JsonProperty(required = true)
    String hypothesisId,

    @JsonPropertyDescription("""
        Treatment variable column name. Must exist in the query results with
        at least two unique values and no NaN. May be categorical.""")
    @JsonProperty(required = true)
    String treatment,

    @JsonPropertyDescription("""
        Outcome variable column name. Must be numeric in the query results
        and contain no NaN.""")
    @JsonProperty(required = true)
    String outcome,

    @JsonPropertyDescription("Treatment form: CONTINUOUS | BINARY_THRESHOLD | CATEGORICAL.")
    @JsonProperty(required = true)
    TreatmentForm treatmentForm,

    @JsonPropertyDescription("""
        Query that returns the analysis data as a DenseQueryDto. Must return
        at least 5 rows and match expectedRowCount.""")
    @JsonProperty(required = true)
    DenseQueryDto dataQuery,

    @JsonPropertyDescription("""
        Expected number of rows the query should return. Integer row count,
        no unit suffix.""")
    @JsonProperty(required = true)
    int expectedRowCount,

    @JsonPropertyDescription("""
        Columns present in the query SELECT that are stripped from analysis
        (identifiers, temporal-ordering columns kept only for structural
        breaks). Stripped columns must NOT be referenced anywhere else in
        the spec. Null when no columns need stripping.""")
    @Nullable List<String> stripColumns,

    @JsonPropertyDescription("""
        DAG edges in digraph notation: semicolon-separated 'A -> B' pairs.
        Must be acyclic, must contain treatment and outcome as nodes, must
        have at least one edge. Every node name must be a column in the
        query results and not in stripColumns.""")
    @JsonProperty(required = true)
    String dagEdges,

    @JsonPropertyDescription("""
        Correlation magnitude threshold for conditional independence
        testing. Dimensionless Pearson correlation magnitude, strictly
        positive, typically in (0, 1].""")
    @JsonProperty(required = true)
    double dsepThreshold,

    @JsonPropertyDescription("""
        Backdoor adjustment set (W matrix columns). Every column must be
        in the query SELECT and not in stripColumns.""")
    @JsonProperty(required = true)
    List<String> adjustmentSet,

    @JsonPropertyDescription("""
        Variables excluded from primary W as mediators, each referencing a
        direct-effect estimation variant that re-includes the mediator in
        W. Null when no mediators are excluded.""")
    @Nullable List<MediatorExclusion> mediatorsExcluded,

    @JsonPropertyDescription("""
        Positivity check config for categorical treatments with more than
        four levels. Null when positivity is not at risk (continuous
        treatments or <= 4-level categoricals).""")
    @Nullable PositivityCheck positivityCheck,

    @JsonPropertyDescription("""
        Estimation variants, one entry per variant. Must be non-empty; all
        ids unique; every column referenced by a variant must be in the
        query SELECT and not in stripColumns.""")
    @JsonProperty(required = true)
    List<EstimationVariant> estimationVariants,

    @JsonPropertyDescription("Quality gate thresholds for nuisance R-squared, sanity, and placebo checks.")
    @JsonProperty(required = true)
    QualityGates gates,

    @JsonPropertyDescription("""
        Mediation analyses, one entry per mediator pathway. Null when no
        mediation analysis is required.""")
    @Nullable List<MediationConfig> mediation,

    @JsonPropertyDescription("""
        GRF heterogeneity configurations. Null when no GRF heterogeneity
        analysis is configured.""")
    @Nullable List<GrfConfig> grfConfigs,

    @JsonPropertyDescription("""
        Refutation checks. PLACEBO, RANDOM_CAUSE, and SUBSET run always;
        TEMPORAL_PLACEBO runs when the data spans multiple time periods.""")
    @Nullable List<RefutationConfig> refutations,

    @JsonPropertyDescription("""
        Sensitivity analyses: confounder drops, confounder adds, threshold
        variants, and alternative model variants.""")
    @JsonProperty(required = true)
    SensitivityConfig sensitivity,

    @JsonPropertyDescription("""
        Structural break configurations at one or more granularity levels.
        Null when no structural-break detection is required.""")
    @Nullable List<StructuralBreakConfig> structuralBreaks,

    @JsonPropertyDescription("Residual diagnostic checks: autocorrelation, field correlation, auto-correction, metadata correlation.")
    @JsonProperty(required = true)
    ResidualChecks residualChecks,

    @JsonPropertyDescription("Range checks: VIF collinearity, propensity overlap, variance degeneracy.")
    @JsonProperty(required = true)
    RangeChecks rangeChecks,

    @JsonPropertyDescription("""
        Unmeasured confounding analyses. Primary variants must include both
        E_VALUE and ROSENBAUM_BOUNDS; sensitivity and scoped variants must
        include at least E_VALUE.""")
    @Nullable List<UnmeasuredConfoundingConfig> unmeasuredConfounding,

    @JsonPropertyDescription("""
        Externalization block: domain rankings and allocation-bias checks.
        Null when neither applies.""")
    @Nullable ExternalizationConfig externalization,

    @JsonPropertyDescription("""
        Contradictions between cited values and recomputed values, one
        entry per discrepancy. Null when there are no discrepancies.""")
    @Nullable List<DiscrepancyEntry> discrepancyLog
) {}
