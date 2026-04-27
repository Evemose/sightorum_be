package com.rorm.viz.dto.data;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.jspecify.annotations.Nullable;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonClassDescription("""
    Influence-curtain payload: ordered signed contributions with a
    running cumulative total.
    
    Ordering:
    The order of `items` is the render order (left-to-right / top-to-
    bottom). `cumulative` values must be consistent with that order
    (cumulative[i] == cumulative[i-1] + contribution[i]).""")
public record InfluenceCurtainData(

    @JsonPropertyDescription("Ordered contribution items. The order is the render order.")
    @JsonProperty(required = true)
    List<Item> items
) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("One contribution in an influence curtain.")
    public record Item(

        @JsonPropertyDescription("Unique item identifier. Also the default visible label.")
        @JsonProperty(required = true)
        String key,

        @JsonPropertyDescription("Signed contribution. Positive extends the curtain up, negative extends down.")
        @JsonProperty(required = true)
        double contribution,

        @JsonPropertyDescription("Running total through this item. Backend pre-computes this.")
        @JsonProperty(required = true)
        double cumulative,

        @JsonPropertyDescription("Optional per-item color role.")
        @Nullable String color
    ) {}
}
