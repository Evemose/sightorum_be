package com.rorm.viz.dto.chart;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.rorm.viz.dto.SharedChartProps;
import com.rorm.viz.dto.data.CompositionData;
import com.rorm.viz.dto.data.FlowData;
import com.rorm.viz.dto.data.HierarchyData;
import com.rorm.viz.dto.data.InfluenceCurtainData;
import com.rorm.viz.dto.data.MarimekkoData;
import com.rorm.viz.dto.data.VoronoiTreemapData;
import org.jspecify.annotations.Nullable;

@JsonClassDescription("Composition-family ChartBlock subtypes (pie, donut, treemap, sankey, ...).")
public final class CompositionChartBlocks {

    private CompositionChartBlocks() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("Circular dendrogram: hierarchical tree drawn on a circular frame.")
    public record CircularDendrogramBlock(

        @JsonPropertyDescription("Optional shared chart scaffolding.")
        @Nullable SharedChartProps scaffold,

        @JsonPropertyDescription("Hierarchy payload. Leaves carry `value`; internal nodes omit it.")
        @JsonProperty(required = true)
        HierarchyData data,

        @JsonPropertyDescription("Optional starting depth for progressive disclosure.")
        @Nullable Integer initialDepth
    ) implements ChartBlock {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("Donut chart. Optional center KPI label rendered inside the hole.")
    public record DonutBlock(

        @JsonPropertyDescription("Optional shared chart scaffolding.")
        @Nullable SharedChartProps scaffold,

        @JsonPropertyDescription("Composition payload. `share` is pre-computed by the backend.")
        @JsonProperty(required = true)
        CompositionData data,

        @JsonPropertyDescription("Optional KPI label rendered in the center hole, e.g. '$1.28M'.")
        @Nullable String centerKpi
    ) implements ChartBlock {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("Influence curtain: ordered signed contributions with a running cumulative total.")
    public record InfluenceCurtainBlock(

        @JsonPropertyDescription("Optional shared chart scaffolding.")
        @Nullable SharedChartProps scaffold,

        @JsonPropertyDescription("Influence-curtain payload. Order is meaningful.")
        @JsonProperty(required = true)
        InfluenceCurtainData data
    ) implements ChartBlock {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("Marimekko: columns of proportional width with stacked segments.")
    public record MarimekkoBlock(

        @JsonPropertyDescription("Optional shared chart scaffolding.")
        @Nullable SharedChartProps scaffold,

        @JsonPropertyDescription("Marimekko payload.")
        @JsonProperty(required = true)
        MarimekkoData data
    ) implements ChartBlock {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("Pie chart. Composition payload with pre-computed shares.")
    public record PieBlock(

        @JsonPropertyDescription("Optional shared chart scaffolding.")
        @Nullable SharedChartProps scaffold,

        @JsonPropertyDescription("Composition payload. `share` is pre-computed by the backend.")
        @JsonProperty(required = true)
        CompositionData data
    ) implements ChartBlock {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("Sankey flow chart.")
    public record SankeyBlock(

        @JsonPropertyDescription("Optional shared chart scaffolding.")
        @Nullable SharedChartProps scaffold,

        @JsonPropertyDescription("Flow payload. Every link.source / link.target MUST match some node.id.")
        @JsonProperty(required = true)
        FlowData data
    ) implements ChartBlock {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("Sunburst chart: nested rings representing a hierarchy.")
    public record SunburstBlock(

        @JsonPropertyDescription("Optional shared chart scaffolding.")
        @Nullable SharedChartProps scaffold,

        @JsonPropertyDescription("Hierarchy payload.")
        @JsonProperty(required = true)
        HierarchyData data
    ) implements ChartBlock {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("Treemap: nested rectangles weighted by leaf `value`.")
    public record TreemapBlock(

        @JsonPropertyDescription("Optional shared chart scaffolding.")
        @Nullable SharedChartProps scaffold,

        @JsonPropertyDescription("Hierarchy payload.")
        @JsonProperty(required = true)
        HierarchyData data
    ) implements ChartBlock {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("Voronoi treemap: polygonal cells weighted by leaf `weight`.")
    public record VoronoiTreemapBlock(

        @JsonPropertyDescription("Optional shared chart scaffolding.")
        @Nullable SharedChartProps scaffold,

        @JsonPropertyDescription("Voronoi-treemap hierarchy payload.")
        @JsonProperty(required = true)
        VoronoiTreemapData data
    ) implements ChartBlock {}
}
