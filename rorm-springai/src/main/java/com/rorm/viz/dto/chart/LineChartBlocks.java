package com.rorm.viz.dto.chart;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.rorm.viz.dto.SharedChartProps;
import com.rorm.viz.dto.data.BandLineData;
import com.rorm.viz.dto.data.GroupedTimeSeriesData;
import com.rorm.viz.dto.data.JoinpointData;
import com.rorm.viz.dto.data.RegimeDef;
import com.rorm.viz.dto.data.TimeSeriesData;
import org.jspecify.annotations.Nullable;

import java.util.List;

@JsonClassDescription("Line-family ChartBlock subtypes (line-chart, area-chart, step-line, regime-shaded, ...).")
public final class LineChartBlocks {

    private LineChartBlocks() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("""
        Area chart: stacked or overlapping filled time series.
        When `stacked = true`, series render stacked on top of each other.""")
    public record AreaChartBlock(

        @JsonPropertyDescription("Optional shared chart scaffolding.")
        @Nullable SharedChartProps scaffold,

        @JsonPropertyDescription("Grouped time-series payload.")
        @JsonProperty(required = true)
        GroupedTimeSeriesData data,

        @JsonPropertyDescription("Optional per-series color overrides aligned to data.series[i].")
        @Nullable List<String> seriesColors,

        @JsonPropertyDescription("When true, series are stacked additively; otherwise overlaid with transparency.")
        @Nullable Boolean stacked
    ) implements ChartBlock {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("Band line: a central line flanked by a [min, max] band at each point.")
    public record BandLineBlock(

        @JsonPropertyDescription("Optional shared chart scaffolding.")
        @Nullable SharedChartProps scaffold,

        @JsonPropertyDescription("Band-line payload. min <= value <= max MUST hold at every point.")
        @JsonProperty(required = true)
        BandLineData data
    ) implements ChartBlock {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("""
        Overlay of a base series vs. a shifted series, with optional
        magnitude labels anchored at specific times.""")
    public record BaseVsShiftedSlopeOverlayBlock(

        @JsonPropertyDescription("Optional shared chart scaffolding.")
        @Nullable SharedChartProps scaffold,

        @JsonPropertyDescription("Grouped time-series payload: typically two series (base + shifted).")
        @JsonProperty(required = true)
        GroupedTimeSeriesData data,

        @JsonPropertyDescription("""
            Optional labels anchored at specific times. Each entry's `t`
            is an ISO-8601 string (not the polymorphic TimeCoord).""")
        @Nullable List<MagnitudeLabel> magnitudeLabels
    ) implements ChartBlock {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("Joinpoint regression chart: piecewise-linear segments with explicit joinpoints.")
    public record JoinpointRegressionBlock(

        @JsonPropertyDescription("Optional shared chart scaffolding.")
        @Nullable SharedChartProps scaffold,

        @JsonPropertyDescription("Joinpoint-regression payload.")
        @JsonProperty(required = true)
        JoinpointData data
    ) implements ChartBlock {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("Standard multi-series line chart with optional smoothing and per-series colors.")
    public record LineChartBlock(

        @JsonPropertyDescription("Optional shared chart scaffolding.")
        @Nullable SharedChartProps scaffold,

        @JsonPropertyDescription("Grouped time-series payload.")
        @JsonProperty(required = true)
        GroupedTimeSeriesData data,

        @JsonPropertyDescription("Optional per-series color overrides aligned to data.series[i].")
        @Nullable List<String> seriesColors,

        @JsonPropertyDescription("When true, the frontend renders smoothed curves; otherwise straight segments.")
        @Nullable Boolean smooth
    ) implements ChartBlock {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("""
        Side-by-side linear- and log-scale view of the same time series.
        Useful for ranges spanning multiple orders of magnitude.""")
    public record LinearLogSideBySideBlock(

        @JsonPropertyDescription("Optional shared chart scaffolding.")
        @Nullable SharedChartProps scaffold,

        @JsonPropertyDescription("Flat time-series payload.")
        @JsonProperty(required = true)
        TimeSeriesData data
    ) implements ChartBlock {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("""
        Regime-shaded line: a time series with background bands
        highlighting named regimes / periods.""")
    public record RegimeShadedLineBlock(

        @JsonPropertyDescription("Optional shared chart scaffolding.")
        @Nullable SharedChartProps scaffold,

        @JsonPropertyDescription("Flat time-series payload.")
        @JsonProperty(required = true)
        TimeSeriesData data,

        @JsonPropertyDescription("Regime bands attached alongside the series. Required.")
        @JsonProperty(required = true)
        List<RegimeDef> regimes
    ) implements ChartBlock {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("Step line: piecewise-constant line with risers at start, middle, or end.")
    public record StepLineBlock(

        @JsonPropertyDescription("Optional shared chart scaffolding.")
        @Nullable SharedChartProps scaffold,

        @JsonPropertyDescription("Flat time-series payload.")
        @JsonProperty(required = true)
        TimeSeriesData data,

        @JsonPropertyDescription("Riser position relative to the data point: 'start', 'middle' (default), or 'end'.")
        @Nullable StepPosition stepPosition
    ) implements ChartBlock {}
}
