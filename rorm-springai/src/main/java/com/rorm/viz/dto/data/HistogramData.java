package com.rorm.viz.dto.data;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.rorm.viz.dto.ColorRole;
import org.jspecify.annotations.Nullable;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonClassDescription("""
    Histogram payload: pre-computed bins plus an optional density
    overlay (KDE / fitted distribution).""")
public record HistogramData(

    @JsonPropertyDescription("Histogram bins in ascending order by `x0`.")
    @JsonProperty(required = true)
    List<Bin> bins,

    @JsonPropertyDescription("""
        Optional KDE overlay points, already in chart coordinates.
        Each element is a 2-tuple [x, density].""")
    @Nullable List<List<Double>> densityPoints
) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("""
        One histogram bin. [x0, x1) is the half-open range; `count` is
        the number of samples falling in the bin.""")
    public record Bin(

        @JsonPropertyDescription("Inclusive lower bound of the bin.")
        @JsonProperty(required = true)
        double x0,

        @JsonPropertyDescription("Exclusive upper bound of the bin.")
        @JsonProperty(required = true)
        double x1,

        @JsonPropertyDescription("Sample count falling in [x0, x1).")
        @JsonProperty(required = true)
        long count,

        @JsonPropertyDescription("Optional bin label rendered on the x-axis.")
        @Nullable String label,

        @JsonPropertyDescription("Optional per-bin color role.")
        @Nullable ColorRole color
    ) {}
}
