package com.rorm.viz.dto;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.jspecify.annotations.Nullable;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonClassDescription("""
    Scaffolding attached to every ChartBlock. Every field is optional;
    omit the whole object when no scaffolding is needed.
    
    Purpose:
    Titles, subtitles, annotations, reference lines, and height hints
    are shared across chart families and live here rather than on each
    chart's `data` payload. The chart-specific shape continues to live
    on `data` / family-specific fields of the ChartBlock subtype.""")
public record SharedChartProps(

    @JsonPropertyDescription("Chart title rendered in the chart header.")
    @Nullable String title,

    @JsonPropertyDescription("Chart subtitle rendered under the title (e.g. '8 weeks, daily rollup').")
    @Nullable String subtitle,

    @JsonPropertyDescription("Overall tint for the chart. Any CSS color value (hex, rgb(), hsl(), named color) or a semantic token (base, base-muted, chrome, severity-amber, severity-muted, severity-low, focal, divergent, delta-positive, delta-negative, error). Per-datum colors still override this.")
    @Nullable String colorRole,

    @JsonPropertyDescription("Optional text annotations anchored to the chart frame or data coordinates.")
    @Nullable List<AnnotationDef> annotations,

    @JsonPropertyDescription("Optional reference lines, each anchored to the x or y axis at a constant value.")
    @Nullable List<ReferenceLineDef> referenceLines,

    @JsonPropertyDescription("Optional pixel-height hint. The frontend may clamp to layout-appropriate bounds.")
    @Nullable Integer height,

    @JsonPropertyDescription("Polarity hint: which numeric direction is favorable. Drives cyan/red mapping on sign-encoded charts and structural extremes on sorted bar/column charts. Defaults to 'higher' when omitted. Ignored by charts without a sign-encoded dimension.")
    @Nullable DesiredDirection desiredDirection
) {}
