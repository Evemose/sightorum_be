package com.rorm.viz.dto.chart;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeInfo.Id;
import com.rorm.viz.dto.SharedChartProps;
import org.jspecify.annotations.Nullable;

@JsonClassDescription("""
    Tagged union of everything a page can render as a chart. The `kind`
    discriminator is kebab-case and enumerates every chart component the
    frontend knows how to render. Unknown `kind` values render nothing
    (removing a kind is a breaking change).
    
    Wire shape:
    ```json
    { "kind": "line-chart", "scaffold": {...}, "data": {...}, ... }
    ```
    
    Every subtype carries an optional `scaffold` (title, annotations,
    reference lines, ...) and a chart-specific data payload. Some
    subtypes carry additional siblings (e.g. `highlightedKeys`,
    `regimes`, `seriesColors`).""")
@JsonTypeInfo(
    use = Id.NAME,
    property = "kind"
)
@JsonSubTypes({
    @JsonSubTypes.Type(value = BarChartBlocks.ColumnOverTimeBlock.class, name = "column-over-time"),
    @JsonSubTypes.Type(value = BarChartBlocks.DivergingBarBlock.class, name = "diverging-bar"),
    @JsonSubTypes.Type(value = BarChartBlocks.DivergingLollipopBlock.class, name = "diverging-lollipop"),
    @JsonSubTypes.Type(value = BarChartBlocks.HorizontalBarBlock.class, name = "horizontal-bar"),
    @JsonSubTypes.Type(value = BarChartBlocks.HorizontalBarWithCutoffBlock.class, name = "horizontal-bar-with-cutoff"),
    @JsonSubTypes.Type(value = BarChartBlocks.LollipopBlock.class, name = "lollipop"),
    @JsonSubTypes.Type(value = BarChartBlocks.PictorialBarBlock.class, name = "pictorial-bar"),
    @JsonSubTypes.Type(value = BarChartBlocks.StackedBarBlock.class, name = "stacked-bar"),
    @JsonSubTypes.Type(value = BarChartBlocks.StackedBar100Block.class, name = "stacked-bar-100"),
    @JsonSubTypes.Type(value = BarChartBlocks.TableLensBlock.class, name = "table-lens"),
    @JsonSubTypes.Type(value = BarChartBlocks.TableWithInlineBarBlock.class, name = "table-with-inline-bar"),
    @JsonSubTypes.Type(value = BarChartBlocks.TornadoBarBlock.class, name = "tornado-bar"),
    @JsonSubTypes.Type(value = BarChartBlocks.VerticalBarBlock.class, name = "vertical-bar"),

    @JsonSubTypes.Type(value = ComparisonChartBlocks.BumpChartBlock.class, name = "bump-chart"),
    @JsonSubTypes.Type(value = ComparisonChartBlocks.DumbbellBlock.class, name = "dumbbell"),
    @JsonSubTypes.Type(value = ComparisonChartBlocks.SlopeChartBlock.class, name = "slope-chart"),
    @JsonSubTypes.Type(value = ComparisonChartBlocks.SmallMultiplesBulletBlock.class, name = "small-multiples-bullet"),
    @JsonSubTypes.Type(value = ComparisonChartBlocks.SmallMultiplesLineBlock.class, name = "small-multiples-line"),

    @JsonSubTypes.Type(value = CompositionChartBlocks.CircularDendrogramBlock.class, name = "circular-dendrogram"),
    @JsonSubTypes.Type(value = CompositionChartBlocks.DonutBlock.class, name = "donut"),
    @JsonSubTypes.Type(value = CompositionChartBlocks.InfluenceCurtainBlock.class, name = "influence-curtain"),
    @JsonSubTypes.Type(value = CompositionChartBlocks.MarimekkoBlock.class, name = "marimekko"),
    @JsonSubTypes.Type(value = CompositionChartBlocks.PieBlock.class, name = "pie"),
    @JsonSubTypes.Type(value = CompositionChartBlocks.SankeyBlock.class, name = "sankey"),
    @JsonSubTypes.Type(value = CompositionChartBlocks.SunburstBlock.class, name = "sunburst"),
    @JsonSubTypes.Type(value = CompositionChartBlocks.TreemapBlock.class, name = "treemap"),
    @JsonSubTypes.Type(value = CompositionChartBlocks.VoronoiTreemapBlock.class, name = "voronoi-treemap"),

    @JsonSubTypes.Type(value = KpiChartBlocks.BulletBlock.class, name = "bullet"),
    @JsonSubTypes.Type(value = KpiChartBlocks.DualMeanCalloutBlock.class, name = "dual-mean-callout"),
    @JsonSubTypes.Type(value = KpiChartBlocks.GaugeBlock.class, name = "gauge"),
    @JsonSubTypes.Type(value = KpiChartBlocks.KpiCardBlock.class, name = "kpi-card"),
    @JsonSubTypes.Type(value = KpiChartBlocks.KpiCardPairBlock.class, name = "kpi-card-pair"),
    @JsonSubTypes.Type(value = KpiChartBlocks.ProgressRingBlock.class, name = "progress-ring"),

    @JsonSubTypes.Type(value = LineChartBlocks.AreaChartBlock.class, name = "area-chart"),
    @JsonSubTypes.Type(value = LineChartBlocks.BandLineBlock.class, name = "band-line"),
    @JsonSubTypes.Type(value = LineChartBlocks.BaseVsShiftedSlopeOverlayBlock.class, name = "base-vs-shifted-slope-overlay"),
    @JsonSubTypes.Type(value = LineChartBlocks.JoinpointRegressionBlock.class, name = "joinpoint-regression"),
    @JsonSubTypes.Type(value = LineChartBlocks.LineChartBlock.class, name = "line-chart"),
    @JsonSubTypes.Type(value = LineChartBlocks.LinearLogSideBySideBlock.class, name = "linear-log-side-by-side"),
    @JsonSubTypes.Type(value = LineChartBlocks.RegimeShadedLineBlock.class, name = "regime-shaded-line"),
    @JsonSubTypes.Type(value = LineChartBlocks.StepLineBlock.class, name = "step-line"),

    @JsonSubTypes.Type(value = MultiChartBlocks.ParallelCoordinatesBlock.class, name = "parallel-coordinates"),
    @JsonSubTypes.Type(value = MultiChartBlocks.RadarBlock.class, name = "radar"),

    @JsonSubTypes.Type(value = DistributionChartBlocks.BeeswarmBlock.class, name = "beeswarm"),
    @JsonSubTypes.Type(value = DistributionChartBlocks.BoxPlotBlock.class, name = "box-plot"),
    @JsonSubTypes.Type(value = DistributionChartBlocks.HistogramBlock.class, name = "histogram"),
    @JsonSubTypes.Type(value = DistributionChartBlocks.PerBucketRateHistogramBlock.class, name = "per-bucket-rate-histogram"),
    @JsonSubTypes.Type(value = DistributionChartBlocks.QqPlotBlock.class, name = "qq-plot"),
    @JsonSubTypes.Type(value = DistributionChartBlocks.ScatterPlotBlock.class, name = "scatter-plot"),
    @JsonSubTypes.Type(value = DistributionChartBlocks.SplitViolinBlock.class, name = "split-violin"),
    @JsonSubTypes.Type(value = DistributionChartBlocks.StripPlotBlock.class, name = "strip-plot"),
    @JsonSubTypes.Type(value = DistributionChartBlocks.ViolinBlock.class, name = "violin"),

    @JsonSubTypes.Type(value = TimeChartBlocks.CalendarHeatmapBlock.class, name = "calendar-heatmap"),
    @JsonSubTypes.Type(value = TimeChartBlocks.CalendarMultiYearStackBlock.class, name = "calendar-multi-year-stack"),
    @JsonSubTypes.Type(value = TimeChartBlocks.StlDecompositionStackBlock.class, name = "stl-decomposition-stack"),
    @JsonSubTypes.Type(value = TimeChartBlocks.WrappedYearOverYearBlock.class, name = "wrapped-year-over-year")
})
public sealed interface ChartBlock permits
    BarChartBlocks.ColumnOverTimeBlock,
    BarChartBlocks.DivergingBarBlock,
    BarChartBlocks.DivergingLollipopBlock,
    BarChartBlocks.HorizontalBarBlock,
    BarChartBlocks.HorizontalBarWithCutoffBlock,
    BarChartBlocks.LollipopBlock,
    BarChartBlocks.PictorialBarBlock,
    BarChartBlocks.StackedBarBlock,
    BarChartBlocks.StackedBar100Block,
    BarChartBlocks.TableLensBlock,
    BarChartBlocks.TableWithInlineBarBlock,
    BarChartBlocks.TornadoBarBlock,
    BarChartBlocks.VerticalBarBlock,
    ComparisonChartBlocks.BumpChartBlock,
    ComparisonChartBlocks.DumbbellBlock,
    ComparisonChartBlocks.SlopeChartBlock,
    ComparisonChartBlocks.SmallMultiplesBulletBlock,
    ComparisonChartBlocks.SmallMultiplesLineBlock,
    CompositionChartBlocks.CircularDendrogramBlock,
    CompositionChartBlocks.DonutBlock,
    CompositionChartBlocks.InfluenceCurtainBlock,
    CompositionChartBlocks.MarimekkoBlock,
    CompositionChartBlocks.PieBlock,
    CompositionChartBlocks.SankeyBlock,
    CompositionChartBlocks.SunburstBlock,
    CompositionChartBlocks.TreemapBlock,
    CompositionChartBlocks.VoronoiTreemapBlock,
    KpiChartBlocks.BulletBlock,
    KpiChartBlocks.DualMeanCalloutBlock,
    KpiChartBlocks.GaugeBlock,
    KpiChartBlocks.KpiCardBlock,
    KpiChartBlocks.KpiCardPairBlock,
    KpiChartBlocks.ProgressRingBlock,
    LineChartBlocks.AreaChartBlock,
    LineChartBlocks.BandLineBlock,
    LineChartBlocks.BaseVsShiftedSlopeOverlayBlock,
    LineChartBlocks.JoinpointRegressionBlock,
    LineChartBlocks.LineChartBlock,
    LineChartBlocks.LinearLogSideBySideBlock,
    LineChartBlocks.RegimeShadedLineBlock,
    LineChartBlocks.StepLineBlock,
    MultiChartBlocks.ParallelCoordinatesBlock,
    MultiChartBlocks.RadarBlock,
    DistributionChartBlocks.BeeswarmBlock,
    DistributionChartBlocks.BoxPlotBlock,
    DistributionChartBlocks.HistogramBlock,
    DistributionChartBlocks.PerBucketRateHistogramBlock,
    DistributionChartBlocks.QqPlotBlock,
    DistributionChartBlocks.ScatterPlotBlock,
    DistributionChartBlocks.SplitViolinBlock,
    DistributionChartBlocks.StripPlotBlock,
    DistributionChartBlocks.ViolinBlock,
    TimeChartBlocks.CalendarHeatmapBlock,
    TimeChartBlocks.CalendarMultiYearStackBlock,
    TimeChartBlocks.StlDecompositionStackBlock,
    TimeChartBlocks.WrappedYearOverYearBlock {

    @Nullable SharedChartProps scaffold();
}
