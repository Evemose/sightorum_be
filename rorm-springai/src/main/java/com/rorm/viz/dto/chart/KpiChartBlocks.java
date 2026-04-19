package com.rorm.viz.dto.chart;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.rorm.viz.dto.SharedChartProps;
import com.rorm.viz.dto.data.DualMeanData;
import com.rorm.viz.dto.data.GaugeData;
import com.rorm.viz.dto.data.ProgressRingData;
import com.rorm.viz.dto.data.ScalarData;
import org.jspecify.annotations.Nullable;

@JsonClassDescription("KPI-family ChartBlock subtypes (kpi-card, gauge, bullet, progress-ring, dual-mean-callout).")
public final class KpiChartBlocks {

    private KpiChartBlocks() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("Bullet chart rendered from a GaugeData payload.")
    public record BulletBlock(

        @JsonPropertyDescription("Optional shared chart scaffolding.")
        @Nullable SharedChartProps scaffold,

        @JsonPropertyDescription("Gauge payload with value / min / max / target / bands.")
        @JsonProperty(required = true)
        GaugeData data
    ) implements ChartBlock {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("Dual-mean callout: two labeled means with an explicit delta.")
    public record DualMeanCalloutBlock(

        @JsonPropertyDescription("Optional shared chart scaffolding.")
        @Nullable SharedChartProps scaffold,

        @JsonPropertyDescription("Dual-mean payload.")
        @JsonProperty(required = true)
        DualMeanData data
    ) implements ChartBlock {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("Gauge chart rendered from a GaugeData payload.")
    public record GaugeBlock(

        @JsonPropertyDescription("Optional shared chart scaffolding.")
        @Nullable SharedChartProps scaffold,

        @JsonPropertyDescription("Gauge payload with value / min / max / target / bands.")
        @JsonProperty(required = true)
        GaugeData data
    ) implements ChartBlock {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("Single KPI card: one scalar value with optional label, sample size, and delta.")
    public record KpiCardBlock(

        @JsonPropertyDescription("Optional shared chart scaffolding.")
        @Nullable SharedChartProps scaffold,

        @JsonPropertyDescription("Scalar payload.")
        @JsonProperty(required = true)
        ScalarData data
    ) implements ChartBlock {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("""
        Paired KPI cards: two scalars rendered side-by-side with an
        explicit top-level delta between them.""")
    public record KpiCardPairBlock(

        @JsonPropertyDescription("Optional shared chart scaffolding.")
        @Nullable SharedChartProps scaffold,

        @JsonPropertyDescription("Left scalar payload.")
        @JsonProperty(required = true)
        ScalarData left,

        @JsonPropertyDescription("Right scalar payload.")
        @JsonProperty(required = true)
        ScalarData right,

        @JsonPropertyDescription("Optional title rendered above the left card.")
        @Nullable String leftTitle,

        @JsonPropertyDescription("Optional title rendered above the right card.")
        @Nullable String rightTitle,

        @JsonPropertyDescription("""
            Top-level signed delta between left and right. Positive ->
            delta-positive color; negative -> delta-negative color.""")
        @JsonProperty(required = true)
        double delta,

        @JsonPropertyDescription("Optional label for the delta, e.g. 'vs last quarter'.")
        @Nullable String deltaLabel
    ) implements ChartBlock {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("Progress ring rendered from a ProgressRingData payload.")
    public record ProgressRingBlock(

        @JsonPropertyDescription("Optional shared chart scaffolding.")
        @Nullable SharedChartProps scaffold,

        @JsonPropertyDescription("Progress-ring payload: value / target / centerLabel.")
        @JsonProperty(required = true)
        ProgressRingData data
    ) implements ChartBlock {}
}
