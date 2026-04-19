package com.rorm.viz.dto.chart;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.rorm.viz.dto.SharedChartProps;
import com.rorm.viz.dto.data.ParallelData;
import com.rorm.viz.dto.data.RadarData;
import org.jspecify.annotations.Nullable;

import java.util.List;

@JsonClassDescription("Multi-dimensional ChartBlock subtypes (parallel-coordinates, radar).")
public final class MultiChartBlocks {

    private MultiChartBlocks() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("""
        Parallel-coordinates chart with optional highlighted keys that
        emphasize specific polylines.""")
    public record ParallelCoordinatesBlock(

        @JsonPropertyDescription("Optional shared chart scaffolding.")
        @Nullable SharedChartProps scaffold,

        @JsonPropertyDescription("Parallel-coordinates payload. line.values.length MUST equal axes.length.")
        @JsonProperty(required = true)
        ParallelData data,

        @JsonPropertyDescription("Optional keys (matched against Line.name) to highlight above the rest.")
        @Nullable List<String> highlightedKeys
    ) implements ChartBlock {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("Radar / spider chart.")
    public record RadarBlock(

        @JsonPropertyDescription("Optional shared chart scaffolding.")
        @Nullable SharedChartProps scaffold,

        @JsonPropertyDescription("Radar payload. series[i].values.length MUST equal indicators.length.")
        @JsonProperty(required = true)
        RadarData data
    ) implements ChartBlock {}
}
