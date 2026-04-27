package com.rorm.viz.dto.chart;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.rorm.viz.dto.SharedChartProps;
import com.rorm.viz.dto.data.CalendarHeatmapData;
import com.rorm.viz.dto.data.CalendarMultiYearData;
import com.rorm.viz.dto.data.StlDecompositionData;
import com.rorm.viz.dto.data.WrappedYoYData;
import org.jspecify.annotations.Nullable;

import java.util.List;

@JsonClassDescription("Time-family ChartBlock subtypes (calendar heatmaps, STL, wrapped-year-over-year).")
public final class TimeChartBlocks {

    private TimeChartBlocks() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("Calendar heatmap for a single year.")
    public record CalendarHeatmapBlock(

        @JsonPropertyDescription("Optional shared chart scaffolding.")
        @Nullable SharedChartProps scaffold,

        @JsonPropertyDescription("Single-year calendar-heatmap payload.")
        @JsonProperty(required = true)
        CalendarHeatmapData data
    ) implements ChartBlock {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("Stacked multi-year calendar heatmaps.")
    public record CalendarMultiYearStackBlock(

        @JsonPropertyDescription("Optional shared chart scaffolding.")
        @Nullable SharedChartProps scaffold,

        @JsonPropertyDescription("Multi-year calendar heatmap payload.")
        @JsonProperty(required = true)
        CalendarMultiYearData data
    ) implements ChartBlock {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("""
        STL decomposition rendered as three stacked panels
        (trend / seasonal / remainder). Optional per-panel colors.""")
    public record StlDecompositionStackBlock(

        @JsonPropertyDescription("Optional shared chart scaffolding.")
        @Nullable SharedChartProps scaffold,

        @JsonPropertyDescription("STL-decomposition payload.")
        @JsonProperty(required = true)
        StlDecompositionData data,

        @JsonPropertyDescription("""
            Optional 3-tuple of colors, one per panel in order:
            [trend, seasonal, remainder]. Length MUST be exactly 3.""")
        @Nullable List<String> panelColors
    ) implements ChartBlock {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("""
        Wrapped year-over-year chart: one line per year on a shared
        Jan..Dec x-axis.""")
    public record WrappedYearOverYearBlock(

        @JsonPropertyDescription("Optional shared chart scaffolding.")
        @Nullable SharedChartProps scaffold,

        @JsonPropertyDescription("Wrapped YoY payload. month MUST be in [1, 12].")
        @JsonProperty(required = true)
        WrappedYoYData data,

        @JsonPropertyDescription("Optional per-series color overrides aligned to data.series[i].")
        @Nullable List<String> seriesColors
    ) implements ChartBlock {}
}
