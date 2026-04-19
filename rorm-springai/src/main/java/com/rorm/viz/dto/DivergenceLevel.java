package com.rorm.viz.dto;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonClassDescription("""
    Direction of a divergence badge:
    - 'high'  -> diverges strongly upward vs. the reference.
    - 'low'   -> diverges strongly downward vs. the reference.
    - 'near'  -> stays close to the reference (no meaningful divergence).""")
public enum DivergenceLevel {
    @JsonProperty("high") HIGH,
    @JsonProperty("low") LOW,
    @JsonProperty("near") NEAR
}
