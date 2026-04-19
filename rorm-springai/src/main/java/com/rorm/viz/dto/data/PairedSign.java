package com.rorm.viz.dto.data;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonClassDescription("""
    Direction hint for a PairedRow. Used by dumbbell / slope charts to
    color the connecting line. Serialized as a lowercase string.""")
public enum PairedSign {
    @JsonProperty("positive") POSITIVE,
    @JsonProperty("negative") NEGATIVE,
    @JsonProperty("neutral") NEUTRAL
}
