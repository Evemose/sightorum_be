package com.rorm.viz.dto;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

@JsonClassDescription("""
    Contiguous index range highlighting a slice of a categorical or
    time-ordered axis. Used by bar-family charts (column-over-time,
    vertical-bar, diverging-lollipop) to shade regions of the x-axis.
    
    Index semantics:
    `start` and `end` are zero-based indices into the chart's ordered
    items/points, inclusive of `start` and exclusive of `end`
    (half-open, like slice notation).""")
public record Section(

    @JsonPropertyDescription("Zero-based inclusive start index into the chart's items / points.")
    @JsonProperty(required = true)
    int start,

    @JsonPropertyDescription("Zero-based exclusive end index into the chart's items / points.")
    @JsonProperty(required = true)
    int end,

    @JsonPropertyDescription("Color used to shade the section. Any CSS color value (hex, rgb(), hsl(), named color) or a semantic token (base, base-muted, chrome, severity-amber, severity-muted, severity-low, focal, divergent, delta-positive, delta-negative, error).")
    @JsonProperty(required = true)
    String color
) {}
