package com.rorm.viz.dto;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonClassDescription("Stroke style for a ReferenceLineDef. Serialized as a plain string.")
public enum ReferenceLineStyle {
    @JsonProperty("solid") SOLID,
    @JsonProperty("dashed") DASHED,
    @JsonProperty("dotted") DOTTED
}
