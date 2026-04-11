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
    One estimation variant: a specific treatment operationalization, model
    type, W matrix, and optional scope filter.""")
public record EstimationVariant(

    @JsonPropertyDescription("Unique id for this variant within the spec.")
    @JsonProperty(required = true)
    String id,

    @JsonPropertyDescription("""
        Treatment column for this variant. Must be in the query SELECT and
        not in stripColumns.""")
    @JsonProperty(required = true)
    String treatmentColumn,

    @JsonPropertyDescription("""
        Treatment form for this variant: CONTINUOUS | BINARY_THRESHOLD |
        CATEGORICAL. May differ from the spec-level treatmentForm when a
        variant binarizes a categorical by pairwise contrast.""")
    @JsonProperty(required = true)
    TreatmentForm treatmentForm,

    @JsonPropertyDescription("""
        Estimator model type token (for example 'LinearDML' or
        'NonParamDML'). Case-sensitive.""")
    @JsonProperty(required = true)
    String modelType,

    @JsonPropertyDescription("""
        W matrix columns for this variant. Every column must be in the query
        SELECT and not in stripColumns.""")
    @JsonProperty(required = true)
    List<String> wColumns,

    @JsonPropertyDescription("""
        Reference category for CATEGORICAL variants (the level used as the
        baseline in contrasts). Required when treatmentForm is CATEGORICAL,
        null otherwise.""")
    @Nullable String referenceCategory,

    @JsonPropertyDescription("""
        Numeric threshold for BINARY_THRESHOLD variants. Values of the
        treatment column above this are coded 1, at-or-below 0. Required
        when treatmentForm is BINARY_THRESHOLD (numeric treatments only),
        null otherwise.""")
    @Nullable Double thresholdValue,

    @JsonPropertyDescription("""
        Optional filter restricting this variant to a subpopulation. Null
        when the variant runs on the full sample. Leaves with GT, LT, or EQ
        operators require a non-empty values list (values[0] is used).""")
    @Nullable VariantFilter filter,

    @JsonPropertyDescription("One-sentence description of what this variant tests.")
    @Nullable String notes
) {}
