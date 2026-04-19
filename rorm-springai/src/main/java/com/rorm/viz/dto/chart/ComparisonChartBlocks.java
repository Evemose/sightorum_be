package com.rorm.viz.dto.chart;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.rorm.viz.dto.ColorRole;
import com.rorm.viz.dto.DivergenceLevel;
import com.rorm.viz.dto.SharedChartProps;
import com.rorm.viz.dto.data.BumpChartData;
import com.rorm.viz.dto.data.PairedComparisonData;
import com.rorm.viz.dto.data.SmallMultiplesBulletData;
import com.rorm.viz.dto.data.SmallMultiplesLineData;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

@JsonClassDescription("Comparison-family ChartBlock subtypes (bump, dumbbell, slope, small-multiples).")
public final class ComparisonChartBlocks {

    private ComparisonChartBlocks() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("Bump chart: rank-over-partitions for multiple named series.")
    public record BumpChartBlock(

        @JsonPropertyDescription("Optional shared chart scaffolding.")
        @Nullable SharedChartProps scaffold,

        @JsonPropertyDescription("Bump-chart payload. ranks.length MUST equal partitions.length per series.")
        @JsonProperty(required = true)
        BumpChartData data,

        @JsonPropertyDescription("""
            Optional per-series color overrides, aligned to
            data.series[i]. Length should equal data.series.size().""")
        @Nullable List<ColorRole> seriesColors
    ) implements ChartBlock {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("Dumbbell chart: paired (left, right) values per row with a connecting line.")
    public record DumbbellBlock(

        @JsonPropertyDescription("Optional shared chart scaffolding.")
        @Nullable SharedChartProps scaffold,

        @JsonPropertyDescription("Paired-comparison payload.")
        @JsonProperty(required = true)
        PairedComparisonData data
    ) implements ChartBlock {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("""
        Slope chart: left/right axis labels, optional divergence map
        per row, and optional aggregate summary on each side.""")
    public record SlopeChartBlock(

        @JsonPropertyDescription("Optional shared chart scaffolding.")
        @Nullable SharedChartProps scaffold,

        @JsonPropertyDescription("Paired-comparison payload.")
        @JsonProperty(required = true)
        PairedComparisonData data,

        @JsonPropertyDescription("Optional label for the left axis.")
        @Nullable String leftLabel,

        @JsonPropertyDescription("Optional label for the right axis.")
        @Nullable String rightLabel,

        @JsonPropertyDescription("""
            Optional divergence level per row, keyed by row key.
            Controls the connecting-line color accent.""")
        @Nullable Map<String, DivergenceLevel> divergenceMap,

        @JsonPropertyDescription("When true, an aggregate summary line is rendered in addition to per-row lines.")
        @Nullable Boolean showAggregate,

        @JsonPropertyDescription("Left-side aggregate value. Only used when showAggregate is true.")
        @Nullable Double aggregateLeft,

        @JsonPropertyDescription("Right-side aggregate value. Only used when showAggregate is true.")
        @Nullable Double aggregateRight
    ) implements ChartBlock {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("Small-multiples bullet grid. Optional explicit column count and panel height.")
    public record SmallMultiplesBulletBlock(

        @JsonPropertyDescription("Optional shared chart scaffolding.")
        @Nullable SharedChartProps scaffold,

        @JsonPropertyDescription("Small-multiples bullet payload.")
        @JsonProperty(required = true)
        SmallMultiplesBulletData data,

        @JsonPropertyDescription("Optional number of grid columns. Frontend auto-flows when omitted.")
        @Nullable Integer columns,

        @JsonPropertyDescription("Optional pixel height per panel.")
        @Nullable Integer panelHeight
    ) implements ChartBlock {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("Small-multiples line grid. Optional explicit column count and panel height.")
    public record SmallMultiplesLineBlock(

        @JsonPropertyDescription("Optional shared chart scaffolding.")
        @Nullable SharedChartProps scaffold,

        @JsonPropertyDescription("Small-multiples line payload.")
        @JsonProperty(required = true)
        SmallMultiplesLineData data,

        @JsonPropertyDescription("Optional number of grid columns. Frontend auto-flows when omitted.")
        @Nullable Integer columns,

        @JsonPropertyDescription("Optional pixel height per panel.")
        @Nullable Integer panelHeight
    ) implements ChartBlock {}
}
