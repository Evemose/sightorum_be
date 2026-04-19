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
    Raw-sample violin payload. The frontend computes the KDE from
    `values`. For pre-computed density curves split by category, see
    SplitViolinData.""")
public record ViolinGroupData(

    @JsonPropertyDescription("Groups plotted side-by-side along the x-axis.")
    @JsonProperty(required = true)
    List<Group> groups
) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("One violin: raw sample values used to compute the density curve.")
    public record Group(

        @JsonPropertyDescription("Group name rendered on the x-axis.")
        @JsonProperty(required = true)
        String name,

        @JsonPropertyDescription("Raw sample values. Must be finite.")
        @JsonProperty(required = true)
        List<Double> values,

        @JsonPropertyDescription("Optional per-group color role.")
        @Nullable ColorRole color
    ) {}
}
