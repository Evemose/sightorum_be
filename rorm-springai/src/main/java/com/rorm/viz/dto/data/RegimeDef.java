package com.rorm.viz.dto.data;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonClassDescription("""
    One shaded regime attached to a regime-shaded line chart. A regime
    spans the time range [from, to] on the x-axis and paints a colored
    background.""")
public record RegimeDef(

    @JsonPropertyDescription("Regime start time: ISO-8601 string (preferred) or epoch milliseconds.")
    @JsonProperty(required = true)
    TimeCoord from,

    @JsonPropertyDescription("Regime end time: ISO-8601 string (preferred) or epoch milliseconds.")
    @JsonProperty(required = true)
    TimeCoord to,

    @JsonPropertyDescription("Optional background color role for the regime band.")
    @Nullable String color,

    @JsonPropertyDescription("Optional label rendered within the regime band.")
    @Nullable String label
) {}
