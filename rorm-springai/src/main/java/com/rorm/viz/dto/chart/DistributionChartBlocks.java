package com.rorm.viz.dto.chart;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.rorm.viz.dto.SharedChartProps;
import com.rorm.viz.dto.data.BoxPlotData;
import com.rorm.viz.dto.data.DistributionPointGroupData;
import com.rorm.viz.dto.data.HistogramData;
import com.rorm.viz.dto.data.PerBucketRateData;
import com.rorm.viz.dto.data.QQPlotData;
import com.rorm.viz.dto.data.ScatterData;
import com.rorm.viz.dto.data.SplitViolinData;
import com.rorm.viz.dto.data.ViolinGroupData;
import org.jspecify.annotations.Nullable;

@JsonClassDescription("Scatter / distribution family ChartBlock subtypes.")
public final class DistributionChartBlocks {

    private DistributionChartBlocks() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("Beeswarm plot: jitter-clouds of individual points per group.")
    public record BeeswarmBlock(

        @JsonPropertyDescription("Optional shared chart scaffolding.")
        @Nullable SharedChartProps scaffold,

        @JsonPropertyDescription("Distribution point group payload.")
        @JsonProperty(required = true)
        DistributionPointGroupData data
    ) implements ChartBlock {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("Box plot: five-number summary plus optional outliers, per group.")
    public record BoxPlotBlock(

        @JsonPropertyDescription("Optional shared chart scaffolding.")
        @Nullable SharedChartProps scaffold,

        @JsonPropertyDescription("Box-plot payload.")
        @JsonProperty(required = true)
        BoxPlotData data
    ) implements ChartBlock {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("Histogram with pre-computed bins and optional density overlay.")
    public record HistogramBlock(

        @JsonPropertyDescription("Optional shared chart scaffolding.")
        @Nullable SharedChartProps scaffold,

        @JsonPropertyDescription("Histogram payload.")
        @JsonProperty(required = true)
        HistogramData data
    ) implements ChartBlock {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("Per-bucket rate histogram: named buckets with counts (categorical x-axis).")
    public record PerBucketRateHistogramBlock(

        @JsonPropertyDescription("Optional shared chart scaffolding.")
        @Nullable SharedChartProps scaffold,

        @JsonPropertyDescription("Per-bucket-rate payload.")
        @JsonProperty(required = true)
        PerBucketRateData data
    ) implements ChartBlock {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("QQ plot: observed-vs-theoretical quantile pairs with a reference line.")
    public record QqPlotBlock(

        @JsonPropertyDescription("Optional shared chart scaffolding.")
        @Nullable SharedChartProps scaffold,

        @JsonPropertyDescription("QQ-plot payload.")
        @JsonProperty(required = true)
        QQPlotData data
    ) implements ChartBlock {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("Scatter plot with optional axis labels.")
    public record ScatterPlotBlock(

        @JsonPropertyDescription("Optional shared chart scaffolding.")
        @Nullable SharedChartProps scaffold,

        @JsonPropertyDescription("Scatter payload.")
        @JsonProperty(required = true)
        ScatterData data,

        @JsonPropertyDescription("Optional x-axis label.")
        @Nullable String xLabel,

        @JsonPropertyDescription("Optional y-axis label.")
        @Nullable String yLabel
    ) implements ChartBlock {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("""
        Split violin: two density curves per category, mirrored across
        the center. Backend MUST reject upstream when only one series
        is available.""")
    public record SplitViolinBlock(

        @JsonPropertyDescription("Optional shared chart scaffolding.")
        @Nullable SharedChartProps scaffold,

        @JsonPropertyDescription("Split-violin payload. `series` must contain exactly two groups.")
        @JsonProperty(required = true)
        SplitViolinData data
    ) implements ChartBlock {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("Strip plot: individual points laid out along a category axis.")
    public record StripPlotBlock(

        @JsonPropertyDescription("Optional shared chart scaffolding.")
        @Nullable SharedChartProps scaffold,

        @JsonPropertyDescription("Distribution point group payload.")
        @JsonProperty(required = true)
        DistributionPointGroupData data
    ) implements ChartBlock {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("Violin plot: KDE computed from raw samples per group.")
    public record ViolinBlock(

        @JsonPropertyDescription("Optional shared chart scaffolding.")
        @Nullable SharedChartProps scaffold,

        @JsonPropertyDescription("Violin-group payload.")
        @JsonProperty(required = true)
        ViolinGroupData data
    ) implements ChartBlock {}
}
