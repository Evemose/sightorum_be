package com.rorm.viz.dto.data;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

@JsonClassDescription("""
    Two-mean callout payload. Renders a side-by-side pair of labels +
    values with an explicit delta between them. Typical use: group-A
    mean vs. group-B mean.""")
public record DualMeanData(

    @JsonPropertyDescription("Label for the first group (left / A).")
    @JsonProperty(required = true)
    String labelA,

    @JsonPropertyDescription("Mean of the first group.")
    @JsonProperty(required = true)
    double valueA,

    @JsonPropertyDescription("Label for the second group (right / B).")
    @JsonProperty(required = true)
    String labelB,

    @JsonPropertyDescription("Mean of the second group.")
    @JsonProperty(required = true)
    double valueB,

    @JsonPropertyDescription("""
        Signed delta, typically valueB - valueA. Positive -> delta-positive
        color; negative -> delta-negative color. Backend pre-computes this
        so the frontend does not have to infer a sign convention.""")
    @JsonProperty(required = true)
    double delta
) {}
