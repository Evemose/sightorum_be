package com.rorm.viz.dto.chart;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

@JsonClassDescription("""
    Overlay label attached to a specific time on the base-vs-shifted
    slope overlay. `t` is an ISO-8601 string (not the polymorphic
    TimeCoord form used elsewhere).""")
public record MagnitudeLabel(

    @JsonPropertyDescription("ISO-8601 time coordinate at which to anchor the label.")
    @JsonProperty(required = true)
    String t,

    @JsonPropertyDescription("Label text rendered at time t.")
    @JsonProperty(required = true)
    String label
) {}
