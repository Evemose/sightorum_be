package com.rorm.viz.dto;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

@JsonClassDescription("""
    Sidebar chip describing an axis / dimension that was considered when
    generating the page. 'fired = true' highlights the axis as relevant
    to the narrative; 'fired = false' renders it as context.""")
public record AxisConsidered(

    @JsonPropertyDescription("Axis / dimension name, e.g. 'segment', 'region', 'channel'.")
    @JsonProperty(required = true)
    String name,

    @JsonPropertyDescription("True when the axis drove the page narrative and should be highlighted.")
    @JsonProperty(required = true)
    boolean fired
) {}
