package com.rorm.ml.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.rorm.ml.dto.pipelinespec.*;
import com.rorm.ml.peristence.MLJobType;
import lombok.Builder;
import org.jspecify.annotations.Nullable;

import java.util.List;

@Builder
@JsonNaming(SnakeCaseStrategy.class)
public record CausalVerificationJobRequest(

    @JsonIgnore String reason,
    String hypothesisId,
    String treatment,
    String outcome,
    TreatmentForm treatmentForm,
    DatasourceConfig datasource,
    int expectedRowCount,
    @Nullable List<String> stripColumns,
    String dagEdges,
    double dsepThreshold,
    List<String> adjustmentSet,
    @Nullable List<MediatorExclusion> mediatorsExcluded,
    @Nullable PositivityCheck positivityCheck,
    List<EstimationVariant> estimationVariants,
    QualityGates gates,
    @Nullable List<MediationConfig> mediation,
    @Nullable List<GrfConfig> grfConfigs,
    @Nullable List<RefutationConfig> refutations,
    SensitivityConfig sensitivity,
    @Nullable List<StructuralBreakConfig> structuralBreaks,
    ResidualChecks residualChecks,
    RangeChecks rangeChecks,
    @Nullable List<UnmeasuredConfoundingConfig> unmeasuredConfounding,
    @Nullable ExternalizationConfig externalization,
    @Nullable List<DiscrepancyEntry> discrepancyLog
) implements AsyncJobRequest {

    @Override
    @JsonIgnore
    public MLJobType jobType() {
        return MLJobType.CAUSAL_VERIFICATION;
    }
}
