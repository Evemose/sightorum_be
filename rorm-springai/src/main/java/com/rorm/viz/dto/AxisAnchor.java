package com.rorm.viz.dto;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonClassDescription("""
    Axis against which a reference line is anchored. 'x' draws a vertical
    line at the given x-value; 'y' draws a horizontal line at the given
    y-value.""")
public enum AxisAnchor {
    @JsonProperty("x") X,
    @JsonProperty("y") Y
}
