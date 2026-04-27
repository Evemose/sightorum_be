package com.rorm.viz.dto.chart;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.rorm.viz.dto.Section;
import com.rorm.viz.dto.SharedChartProps;
import com.rorm.viz.dto.data.*;
import org.jspecify.annotations.Nullable;

import java.util.List;

@JsonClassDescription("Bar-family ChartBlock subtypes. All implement ChartBlock; see kind discriminators on ChartBlock.")
public final class BarChartBlocks {

    private BarChartBlocks() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("""
        Vertical-columns-over-time. Combines a time-series payload with
        optional x-axis sections (shaded ranges) and an optional subset
        of highlighted keys.""")
    public record ColumnOverTimeBlock(

        @JsonPropertyDescription("Optional shared chart scaffolding.")
        @Nullable SharedChartProps scaffold,

        @JsonPropertyDescription("Time-series data powering the columns.")
        @JsonProperty(required = true)
        TimeSeriesData data,

        @JsonPropertyDescription("Optional shaded sections along the x-axis.")
        @Nullable List<Section> sections,

        @JsonPropertyDescription("""
            Optional highlight keys. Entries may be string keys matching
            data points OR numeric epoch indices. Carried as Object to
            preserve wire shape.""")
        @Nullable List<Object> highlightedKeys,

        @JsonPropertyDescription("Color role used for highlighted entries.")
        @Nullable String highlightColor
    ) implements ChartBlock {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("Horizontal diverging-bar chart. Signed values diverge left/right of a center axis.")
    public record DivergingBarBlock(

        @JsonPropertyDescription("Optional shared chart scaffolding.")
        @Nullable SharedChartProps scaffold,

        @JsonPropertyDescription("""
            Categorical series. Values are SIGNED: positive diverges
            right of the axis, negative diverges left.""")
        @JsonProperty(required = true)
        CategoricalSeriesData data
    ) implements ChartBlock {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("Diverging lollipop variant. Optional pre-sort flag and shaded sections.")
    public record DivergingLollipopBlock(

        @JsonPropertyDescription("Optional shared chart scaffolding.")
        @Nullable SharedChartProps scaffold,

        @JsonPropertyDescription("Categorical series. Values are SIGNED (see DivergingBarBlock).")
        @JsonProperty(required = true)
        CategoricalSeriesData data,

        @JsonPropertyDescription("""
            When true, the frontend re-sorts items by absolute value
            descending. Otherwise the emit order is preserved.""")
        @Nullable Boolean sorted,

        @JsonPropertyDescription("Optional shaded sections along the category axis.")
        @Nullable List<Section> sections
    ) implements ChartBlock {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("""
        Horizontal bar chart with an explicit cutoff rank. Categories
        below the cutoff are rolled up or styled differently.""")
    public record HorizontalBarBlock(

        @JsonPropertyDescription("Optional shared chart scaffolding.")
        @Nullable SharedChartProps scaffold,

        @JsonPropertyDescription("Categorical series in render order.")
        @JsonProperty(required = true)
        CategoricalSeriesData data,

        @JsonPropertyDescription("""
            Rank index (0-based) above which items are treated as
            'above the cutoff'. Required.""")
        @JsonProperty(required = true)
        int cutoffRank
    ) implements ChartBlock {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("Horizontal bar chart variant with an implicit cutoff and optional pre-sort flag.")
    public record HorizontalBarWithCutoffBlock(

        @JsonPropertyDescription("Optional shared chart scaffolding.")
        @Nullable SharedChartProps scaffold,

        @JsonPropertyDescription("Categorical series in render order.")
        @JsonProperty(required = true)
        CategoricalSeriesData data,

        @JsonPropertyDescription("When true, the frontend re-sorts items by value descending.")
        @Nullable Boolean sorted
    ) implements ChartBlock {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("Lollipop chart: bars drawn as stems ending in a marker. Optional marker shape.")
    public record LollipopBlock(

        @JsonPropertyDescription("Optional shared chart scaffolding.")
        @Nullable SharedChartProps scaffold,

        @JsonPropertyDescription("Categorical series in render order.")
        @JsonProperty(required = true)
        CategoricalSeriesData data,

        @JsonPropertyDescription("""
            Optional marker shape identifier (frontend-defined tokens,
            e.g. 'circle', 'square', 'diamond'). Unknown shapes fall
            back to the default.""")
        @Nullable String shape
    ) implements ChartBlock {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("Pictorial-bar chart: bars rendered as stacked / tiled pictograms.")
    public record PictorialBarBlock(

        @JsonPropertyDescription("Optional shared chart scaffolding.")
        @Nullable SharedChartProps scaffold,

        @JsonPropertyDescription("Categorical series in render order.")
        @JsonProperty(required = true)
        CategoricalSeriesData data
    ) implements ChartBlock {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("Stacked bar chart. Optional `horizontal` flag orients bars sideways.")
    public record StackedBarBlock(

        @JsonPropertyDescription("Optional shared chart scaffolding.")
        @Nullable SharedChartProps scaffold,

        @JsonPropertyDescription("Stacked-series payload. series[i].values.length MUST equal categories.length.")
        @JsonProperty(required = true)
        StackedSeriesData data,

        @JsonPropertyDescription("When true, bars run horizontally; otherwise vertically.")
        @Nullable Boolean horizontal
    ) implements ChartBlock {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("""
        100%-stacked bar chart. Each stack is normalized to 100% of its
        column. Optional `horizontal` flag orients bars sideways.""")
    public record StackedBar100Block(

        @JsonPropertyDescription("Optional shared chart scaffolding.")
        @Nullable SharedChartProps scaffold,

        @JsonPropertyDescription("Stacked-series payload. series[i].values.length MUST equal categories.length.")
        @JsonProperty(required = true)
        StackedSeriesData data,

        @JsonPropertyDescription("When true, bars run horizontally; otherwise vertically.")
        @Nullable Boolean horizontal
    ) implements ChartBlock {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("Table-lens rendering: a dense table with inline bars for the primary value.")
    public record TableLensBlock(

        @JsonPropertyDescription("Optional shared chart scaffolding.")
        @Nullable SharedChartProps scaffold,

        @JsonPropertyDescription("Table-with-inline-bar payload.")
        @JsonProperty(required = true)
        TableInlineBarData data
    ) implements ChartBlock {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("Table with inline horizontal bars. Optional column header for the `key` column.")
    public record TableWithInlineBarBlock(

        @JsonPropertyDescription("Optional shared chart scaffolding.")
        @Nullable SharedChartProps scaffold,

        @JsonPropertyDescription("Table-with-inline-bar payload.")
        @JsonProperty(required = true)
        TableInlineBarData data,

        @JsonPropertyDescription("Optional header label for the row-key column.")
        @Nullable String keyLabel
    ) implements ChartBlock {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("Tornado chart: paired left/right magnitudes per row.")
    public record TornadoBarBlock(

        @JsonPropertyDescription("Optional shared chart scaffolding.")
        @Nullable SharedChartProps scaffold,

        @JsonPropertyDescription("Tornado payload: paired left/right magnitudes per row.")
        @JsonProperty(required = true)
        TornadoData data
    ) implements ChartBlock {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("Vertical bar chart with optional shaded sections along the category axis.")
    public record VerticalBarBlock(

        @JsonPropertyDescription("Optional shared chart scaffolding.")
        @Nullable SharedChartProps scaffold,

        @JsonPropertyDescription("Categorical series in render order.")
        @JsonProperty(required = true)
        CategoricalSeriesData data,

        @JsonPropertyDescription("Optional shaded sections along the category axis.")
        @Nullable List<Section> sections
    ) implements ChartBlock {}
}
