package com.rorm.viz.dto.data;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.jspecify.annotations.Nullable;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonClassDescription("Small-multiples bullet payload: one bullet panel per named group.")
public record SmallMultiplesBulletData(

    @JsonPropertyDescription("Panels, each rendered as its own bullet chart.")
    @JsonProperty(required = true)
    List<Panel> panels
) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("""
        One bullet panel with a value, a target, and optional shaded
        bands on the value axis.""")
    public record Panel(

        @JsonPropertyDescription("Panel name rendered above the panel.")
        @JsonProperty(required = true)
        String name,

        @JsonPropertyDescription("Current value rendered as the filled bar.")
        @JsonProperty(required = true)
        double value,

        @JsonPropertyDescription("Target value rendered as the target marker.")
        @JsonProperty(required = true)
        double target,

        @JsonPropertyDescription("Optional shaded bands painting the panel track in semantic zones.")
        @Nullable List<Band> bands
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("One colored band on a bullet panel track.")
    public record Band(

        @JsonPropertyDescription("Inclusive lower bound of the band.")
        @JsonProperty(required = true)
        double from,

        @JsonPropertyDescription("Exclusive upper bound of the band.")
        @JsonProperty(required = true)
        double to,

        @JsonPropertyDescription("Optional color role used to paint the band.")
        @Nullable String color
    ) {}
}
