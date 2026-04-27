package com.rorm.viz.dto.data;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.jspecify.annotations.Nullable;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonClassDescription("""
    Bump-chart payload: rank-over-time for multiple named series.
    
    Invariants:
    - Ranks are 1-BASED integers (1 = top rank).
    - Every series's `ranks.length === partitions.length`.""")
public record BumpChartData(

    @JsonPropertyDescription("X-axis labels (time buckets). Rendered left-to-right in list order.")
    @JsonProperty(required = true)
    List<String> partitions,

    @JsonPropertyDescription("Series to plot. Each series has one rank per partition.")
    @JsonProperty(required = true)
    List<Series> series
) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("One named series. ranks.length MUST equal partitions.length.")
    public record Series(

        @JsonPropertyDescription("Series name rendered in the legend.")
        @JsonProperty(required = true)
        String name,

        @JsonPropertyDescription("Optional per-series color role.")
        @Nullable String color,

        @JsonPropertyDescription("""
            1-based ranks, one per partition. Length MUST equal
            partitions.length.""")
        @JsonProperty(required = true)
        List<Integer> ranks
    ) {}
}
