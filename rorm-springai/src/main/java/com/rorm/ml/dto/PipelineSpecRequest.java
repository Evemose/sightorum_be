package com.rorm.ml.dto;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.rorm.dto.dense.DenseQueryDto;
import lombok.Builder;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

@Builder
@JsonClassDescription("""
    Pipeline specification for causal verification.
    Uses a DenseQueryDto to define the data source query against the schema,
    which will be compiled to SQL before submission to the pipeline.""")
public record PipelineSpecRequest(
    String hypothesisId,

    @JsonPropertyDescription("Treatment variable column name.")
    String treatment,

    @JsonPropertyDescription("Outcome variable column name.")
    String outcome,

    @JsonPropertyDescription("CONTINUOUS, BINARY_THRESHOLD, or CATEGORICAL.")
    String treatmentForm,

    @JsonPropertyDescription("Query that returns the analysis data. Uses the same schema as executeQuery.")
    DenseQueryDto dataQuery,

    @JsonPropertyDescription("Expected number of rows the query should return (sanity check).")
    int expectedRowCount,

    @Nullable List<String> stripColumns,

    @JsonPropertyDescription("DAG edges as semicolon-separated 'A->B' pairs.")
    String dagEdges,

    double dsepThreshold,

    List<String> adjustmentSet,

    @Nullable List<Map<String, Object>> mediatorsExcluded,

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
) {}
