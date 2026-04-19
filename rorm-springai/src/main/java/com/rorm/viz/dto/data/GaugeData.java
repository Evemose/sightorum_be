package com.rorm.viz.dto.data;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.rorm.viz.dto.ColorRole;
import org.jspecify.annotations.Nullable;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonClassDescription("""
    Payload for gauge and bullet charts.
    
    Axis conventions:
    - [min, max] defines the gauge's numeric range.
    - 'target' (if supplied) must lie in [min, max].
    - Each band's [from, to] must lie in [min, max]; bands may overlap
      but should not extend outside the range.""")
public record GaugeData(

    @JsonPropertyDescription("Current value, must lie in [min, max].")
    @JsonProperty(required = true)
    double value,

    @JsonPropertyDescription("Lower bound of the gauge range.")
    @JsonProperty(required = true)
    double min,

    @JsonPropertyDescription("Upper bound of the gauge range.")
    @JsonProperty(required = true)
    double max,

    @JsonPropertyDescription("Optional target marker inside [min, max].")
    @Nullable Double target,

    @JsonPropertyDescription("Optional shaded bands painting the gauge track in semantic zones.")
    @Nullable List<Band> bands
) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("One colored band on a gauge track. [from, to] should lie within [min, max].")
    public record Band(

        @JsonPropertyDescription("Optional band label, e.g. 'warn', 'good'.")
        @Nullable String label,

        @JsonPropertyDescription("Inclusive lower bound of the band.")
        @JsonProperty(required = true)
        double from,

        @JsonPropertyDescription("Exclusive upper bound of the band.")
        @JsonProperty(required = true)
        double to,

        @JsonPropertyDescription("Color role used to paint the band.")
        @Nullable ColorRole color
    ) {}
}
