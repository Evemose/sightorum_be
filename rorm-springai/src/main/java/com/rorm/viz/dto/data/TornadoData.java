package com.rorm.viz.dto.data;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.jspecify.annotations.Nullable;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonClassDescription("""
    Payload for tornado / diverging paired bar charts.
    
    Sign convention:
    `left` and `right` are POSITIVE magnitudes. The chart renders `left`
    extending leftward from the axis and `right` extending rightward.
    Do not encode direction via sign; it is encoded by side.""")
public record TornadoData(

    @JsonPropertyDescription("Rows in render order. Visual top-to-bottom order = list order.")
    @JsonProperty(required = true)
    List<TornadoRow> rows,

    @JsonPropertyDescription("Legend label for the left side.")
    @JsonProperty(required = true)
    String leftName,

    @JsonPropertyDescription("Legend label for the right side.")
    @JsonProperty(required = true)
    String rightName
) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("""
        One paired row. `left` and `right` are POSITIVE magnitudes that
        extend away from the center axis.""")
    public record TornadoRow(

        @JsonPropertyDescription("Unique row identifier. Also the default display label.")
        @JsonProperty(required = true)
        String key,

        @JsonPropertyDescription("Positive magnitude extending to the LEFT of the axis.")
        @JsonProperty(required = true)
        double left,

        @JsonPropertyDescription("Positive magnitude extending to the RIGHT of the axis.")
        @JsonProperty(required = true)
        double right,

        @JsonPropertyDescription("Optional label for the left bar (e.g. formatted magnitude).")
        @Nullable String leftLabel,

        @JsonPropertyDescription("Optional label for the right bar (e.g. formatted magnitude).")
        @Nullable String rightLabel
    ) {}
}
