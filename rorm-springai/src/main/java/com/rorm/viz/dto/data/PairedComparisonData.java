package com.rorm.viz.dto.data;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.jspecify.annotations.Nullable;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonClassDescription("""
    Payload for dumbbell and slope charts. Each row has a left value
    and a right value connected by a line.""")
public record PairedComparisonData(

    @JsonPropertyDescription("Rows in render order. The frontend preserves the given order.")
    @JsonProperty(required = true)
    List<PairedRow> rows
) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("""
        One paired row. `left` and `right` are SIGNED values on the same
        scale; the optional `sign` hint pins the connecting-line color.""")
    public record PairedRow(

        @JsonPropertyDescription("Unique row identifier.")
        @JsonProperty(required = true)
        String key,

        @JsonPropertyDescription("Left (reference / before) value.")
        @JsonProperty(required = true)
        double left,

        @JsonPropertyDescription("Right (comparison / after) value.")
        @JsonProperty(required = true)
        double right,

        @JsonPropertyDescription("""
            Optional direction hint for the connecting line:
            'positive', 'negative', or 'neutral'. Backend can pre-compute
            from (right - left) or leave null for the frontend to infer.""")
        @Nullable PairedSign sign
    ) {}
}
