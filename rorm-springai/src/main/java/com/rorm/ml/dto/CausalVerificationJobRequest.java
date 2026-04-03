package com.rorm.ml.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.rorm.ml.peristence.MLJobType;
import lombok.Builder;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

@Builder
@JsonNaming(SnakeCaseStrategy.class)
public record CausalVerificationJobRequest(
    @JsonIgnore String reason,
    String hypothesisId,
    String treatment,
    String outcome,
    String treatmentForm,
    DatasourceConfig datasource,
    int expectedRowCount,
    @Nullable List<String> stripColumns,
    String dagEdges,
    double dsepThreshold,
    List<String> adjustmentSet,
    @Nullable List<Map<String, Object>> mediatorsExcluded,
    @Nullable Map<String, Object> positivityCheck,
    List<Map<String, Object>> estimationVariants,
    Map<String, Object> gates,
    @Nullable List<Map<String, Object>> mediation,
    @Nullable List<Map<String, Object>> grfConfigs,
    @Nullable List<Map<String, Object>> refutations,
    Map<String, Object> sensitivity,
    @Nullable List<Map<String, Object>> structuralBreaks,
    Map<String, Object> residualChecks,
    Map<String, Object> rangeChecks,
    @Nullable List<Map<String, Object>> unmeasuredConfounding,
    @Nullable Map<String, Object> externalization,
    @Nullable List<Map<String, Object>> discrepancyLog
) implements AsyncJobRequest {

    @Override
    @JsonIgnore
    public MLJobType jobType() {
        return MLJobType.CAUSAL_VERIFICATION;
    }
}
