package com.rorm.ai.swarm.dto;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

@JsonClassDescription("Structured pipeline specification extracted from executor compiler output")
public record PipelineCompilationDTO(

    @JsonPropertyDescription("Hypothesis identifier (e.g. H1)")
    @JsonProperty(required = true)
    String hypothesisId,

    @JsonPropertyDescription("Treatment variable column name")
    @JsonProperty(required = true)
    String treatment,

    @JsonPropertyDescription("Outcome variable column name")
    @JsonProperty(required = true)
    String outcome,

    @JsonPropertyDescription("Treatment form: CONTINUOUS, BINARY_THRESHOLD, or CATEGORICAL")
    @JsonProperty(required = true)
    String treatmentForm,

    @JsonPropertyDescription("DAG edges as semicolon-separated 'A->B' pairs")
    @JsonProperty(required = true)
    String dagEdges,

    @JsonPropertyDescription("D-separation test threshold")
    @Nullable Double dsepThreshold,

    @JsonPropertyDescription("Adjustment set variables for backdoor criterion")
    @Nullable List<String> adjustmentSet,

    @JsonPropertyDescription("Estimation method variants to run")
    @Nullable List<Map<String, Object>> estimationVariants,

    @JsonPropertyDescription("Quality gate thresholds")
    @Nullable Map<String, Object> gates,

    @JsonPropertyDescription("Sensitivity analysis configuration")
    @Nullable Map<String, Object> sensitivity,

    @JsonPropertyDescription("Residual diagnostic checks")
    @Nullable Map<String, Object> residualChecks,

    @JsonPropertyDescription("Range and overlap checks")
    @Nullable Map<String, Object> rangeChecks
) {}
