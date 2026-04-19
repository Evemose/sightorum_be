package com.rorm.ml.dto;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.rorm.ml.dto.pipelinespec.*;
import com.rorm.ml.dto.pipelinespec.RangeChecks.*;
import org.jspecify.annotations.Nullable;

import java.util.List;

@JsonNaming(SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonClassDescription("""
    Partial pipeline spec patch — include ONLY the fields you want to change.
    Omitted fields keep the base run's values. Nested objects are deep-merged:
    e.g. providing only gates.sanity changes sanity thresholds while keeping
    nuisance_r2 and placebo unchanged. Lists are replaced wholesale.""")
public record PipelineSpecPatch(

    @Nullable @JsonPropertyDescription("Treatment variable column name.")
    String treatment,

    @Nullable @JsonPropertyDescription("Outcome variable column name.")
    String outcome,

    @Nullable @JsonPropertyDescription("Treatment form: CONTINUOUS | BINARY_THRESHOLD | CATEGORICAL.")
    TreatmentForm treatmentForm,

    @Nullable @JsonPropertyDescription("""
        DAG edges in digraph notation: semicolon-separated 'A -> B' pairs.
        Must be acyclic, must contain treatment and outcome as nodes.""")
    String dagEdges,

    @Nullable @JsonPropertyDescription("Correlation magnitude threshold for d-sep testing. Strictly positive, in (0, 1].")
    Double dsepThreshold,

    @Nullable @JsonPropertyDescription("Backdoor adjustment set (W matrix columns). Replaces the entire list.")
    List<String> adjustmentSet,

    @Nullable @JsonPropertyDescription("Columns stripped from analysis. Replaces the entire list.")
    List<String> stripColumns,

    @Nullable @JsonPropertyDescription("Mediators excluded from primary W. Replaces the entire list.")
    List<MediatorExclusion> mediatorsExcluded,

    @Nullable @JsonPropertyDescription("Positivity check config for categorical treatments.")
    PositivityCheck positivityCheck,

    @Nullable @JsonPropertyDescription("Estimation variants. Replaces the entire list; all ids must be unique.")
    List<EstimationVariant> estimationVariants,

    @Nullable @JsonPropertyDescription("Quality gate thresholds. Deep-merged: provide only the sub-object to change.")
    GatesPatch gates,

    @Nullable @JsonPropertyDescription("Mediation analyses. Replaces the entire list.")
    List<MediationConfig> mediation,

    @Nullable @JsonPropertyDescription("GRF heterogeneity configurations. Replaces the entire list.")
    List<GrfConfig> grfConfigs,

    @Nullable @JsonPropertyDescription("Refutation checks. Replaces the entire list.")
    List<RefutationConfig> refutations,

    @Nullable @JsonPropertyDescription("Sensitivity analyses. Deep-merged: provide only the list to change.")
    SensitivityPatch sensitivity,

    @Nullable @JsonPropertyDescription("Structural break configurations. Replaces the entire list.")
    List<StructuralBreakConfig> structuralBreaks,

    @Nullable @JsonPropertyDescription("Residual diagnostic checks. Deep-merged: provide only the sub-object to change.")
    ResidualChecksPatch residualChecks,

    @Nullable @JsonPropertyDescription("Range checks. Deep-merged: provide only the sub-object to change.")
    RangeChecksPatch rangeChecks,

    @Nullable @JsonPropertyDescription("Unmeasured confounding analyses. Replaces the entire list.")
    List<UnmeasuredConfoundingConfig> unmeasuredConfounding,

    @Nullable @JsonPropertyDescription("Externalization block. Deep-merged: provide only the list to change.")
    ExternalizationPatch externalization
) {

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("Partial quality gates — provide only the sub-gate to change.")
    public record GatesPatch(
        @Nullable @JsonPropertyDescription("Nuisance-model R² thresholds.")
        NuisanceR2Patch nuisanceR2,
        @Nullable @JsonPropertyDescription("Sanity check on expected direction and magnitude.")
        SanityPatch sanity,
        @Nullable @JsonPropertyDescription("Placebo test flag ratio.")
        PlaceboPatch placebo
    ) {
        @JsonNaming(SnakeCaseStrategy.class)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public record NuisanceR2Patch(
            @Nullable @JsonPropertyDescription("Outcome model R² abort threshold. Decimal in [0,1].") Double outcomeAbort,
            @Nullable @JsonPropertyDescription("Outcome model R² flag threshold.") Double outcomeFlag,
            @Nullable @JsonPropertyDescription("Treatment model R² abort threshold.") Double treatmentAbort,
            @Nullable @JsonPropertyDescription("Treatment model R² flag threshold.") Double treatmentFlag,
            @Nullable @JsonPropertyDescription("Structural max R² for treatment distribution.") Double treatmentStructuralMaxR2
        ) {}

        @JsonNaming(SnakeCaseStrategy.class)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public record SanityPatch(
            @Nullable @JsonPropertyDescription("Expected sign of ATE: -1 or +1.") Integer expectedDirection,
            @Nullable @JsonPropertyDescription("Effect magnitude abort threshold.") Double abortMagnitude,
            @Nullable @JsonPropertyDescription("Effect magnitude flag threshold.") Double flagMagnitude
        ) {}

        @JsonNaming(SnakeCaseStrategy.class)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public record PlaceboPatch(
            @Nullable @JsonPropertyDescription("Ratio |placebo ATE|/|real ATE| above which placebo flags.") Double flagRatio
        ) {}
    }

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("Partial residual checks — provide only the field to change.")
    public record ResidualChecksPatch(
        @Nullable @JsonPropertyDescription("Autocorrelation checks. Replaces the entire list.")
        List<ResidualChecks.AutocorrelationCheck> autocorrelation,
        @Nullable @JsonPropertyDescription("Field-correlation scan config.")
        FieldCorrelationPatch fieldCorrelation,
        @Nullable @JsonPropertyDescription("Auto-correction loop config.")
        AutoCorrectionPatch autoCorrection,
        @Nullable @JsonPropertyDescription("Metadata correlation checks. Replaces the entire list.")
        List<ResidualChecks.MetadataCorrelation> metadataCorrelation
    ) {
        @JsonNaming(SnakeCaseStrategy.class)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public record FieldCorrelationPatch(
            @Nullable @JsonPropertyDescription("Correlation threshold in (0,1].") Double threshold,
            @Nullable @JsonPropertyDescription("Columns to check. Replaces the entire list.") List<String> checkColumns
        ) {}

        @JsonNaming(SnakeCaseStrategy.class)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public record AutoCorrectionPatch(
            @Nullable @JsonPropertyDescription("Max iterations. Must be > 0.") Integer maxIterations,
            @Nullable @JsonPropertyDescription("Stop criterion on CI width relative change.") Double stopCriterionCiPct
        ) {}
    }

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("Partial range checks — provide only the field to change.")
    public record RangeChecksPatch(
        @Nullable @JsonPropertyDescription("VIF collinearity config.")
        VifPatch vif,
        @Nullable @JsonPropertyDescription("Propensity overlap checks. Replaces the entire list.")
        List<OverlapCheck> overlap,
        @Nullable @JsonPropertyDescription("Variance notes. Replaces the entire list.")
        List<VarianceCheck> variance
    ) {
        @JsonNaming(SnakeCaseStrategy.class)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public record VifPatch(
            @Nullable @JsonPropertyDescription("VIF magnitude threshold.") Double threshold,
            @Nullable @JsonPropertyDescription("Keep/drop decisions. Replaces the entire list.") List<VifDropPair> dropPairs
        ) {}
    }

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("Partial sensitivity config — provide only the list to change.")
    public record SensitivityPatch(
        @Nullable @JsonPropertyDescription("Confounder-drop tests. Replaces the entire list.")
        List<SensitivityConfig.ConfounderDrop> confounderDrops,
        @Nullable @JsonPropertyDescription("Confounder-add tests. Replaces the entire list.")
        List<SensitivityConfig.ConfounderAdd> confounderAdds,
        @Nullable @JsonPropertyDescription("Threshold variants for BINARY_THRESHOLD. Replaces the entire list.")
        List<SensitivityConfig.ThresholdVariant> thresholdVariants,
        @Nullable @JsonPropertyDescription("Alternative-model variants. Replaces the entire list.")
        List<SensitivityConfig.ModelVariant> modelVariants
    ) {}

    @JsonNaming(SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("Partial externalization config — provide only the list to change.")
    public record ExternalizationPatch(
        @Nullable @JsonPropertyDescription("Domain-predicted orderings. Replaces the entire list.")
        List<ExternalizationConfig.DomainRanking> domainRankings,
        @Nullable @JsonPropertyDescription("Allocation-bias checks. Replaces the entire list.")
        List<ExternalizationConfig.AllocationBias> allocationBias
    ) {}
}
