package com.rorm.viz.dto.data;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonClassDescription("""
    Single-number payload for KPI-card and similar scalar readouts.
    Numeric values must be finite; replace NaN/Infinity with null upstream.""")
public record ScalarData(

    @JsonPropertyDescription("The scalar value rendered as the hero number.")
    @JsonProperty(required = true)
    double value,

    @JsonPropertyDescription("Optional label rendered above / below the value, e.g. 'Revenue'.")
    @Nullable String label,

    @JsonPropertyDescription("Optional sample size. Rendered as a small chip next to the value.")
    @Nullable Integer n,

    @JsonPropertyDescription("""
        Optional change vs. a comparison period. Signed. Positive values
        render with delta-positive color, negative with delta-negative.""")
    @Nullable Double delta,

    @JsonPropertyDescription("Optional label for the delta, e.g. 'vs last week'.")
    @Nullable String deltaLabel
) {}
