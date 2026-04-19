package com.rorm.viz.dto.data;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonClassDescription("Payload for a progress ring. Fill ratio = value / target, clamped to [0, 1] on render.")
public record ProgressRingData(

    @JsonPropertyDescription("Current value.")
    @JsonProperty(required = true)
    double value,

    @JsonPropertyDescription("Target value. Must be > 0. Fill = value / target.")
    @JsonProperty(required = true)
    double target,

    @JsonPropertyDescription("Optional label rendered in the center of the ring, e.g. '73%', '$12K'.")
    @Nullable String centerLabel
) {}
