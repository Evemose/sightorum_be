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
    Payload for bar, diverging-bar, lollipop, and related single-series
    categorical charts.
    
    Ordering:
    The order of `items` is the intended render order. The frontend
    does NOT re-sort unless the chart's `sorted: true` flag explicitly
    asks it to.""")
public record CategoricalSeriesData(

    @JsonPropertyDescription("Categorical items in render order (one per category).")
    @JsonProperty(required = true)
    List<CategoricalItem> items
) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("""
        One category. `key` is the unique identifier and the default
        visible label; supply `label` only to override display.""")
    public record CategoricalItem(

        @JsonPropertyDescription("Unique category identifier. Also used as the display label when `label` is absent.")
        @JsonProperty(required = true)
        String key,

        @JsonPropertyDescription("Numeric value for this category. Must be finite.")
        @JsonProperty(required = true)
        double value,

        @JsonPropertyDescription("Optional per-item color role overriding the chart default.")
        @Nullable ColorRole color,

        @JsonPropertyDescription("Optional display-only label. Falls back to `key` when omitted.")
        @Nullable String label
    ) {}
}
