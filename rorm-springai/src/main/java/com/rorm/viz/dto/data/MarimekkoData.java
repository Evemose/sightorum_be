package com.rorm.viz.dto.data;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.jspecify.annotations.Nullable;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonClassDescription("""
    Marimekko payload: a row of columns with proportional widths, each
    containing stacked segments.
    
    Width semantics:
    Column widths are proportional - the frontend normalizes them to
    100% of the available horizontal space.""")
public record MarimekkoData(

    @JsonPropertyDescription("Columns in left-to-right render order.")
    @JsonProperty(required = true)
    List<MarimekkoColumn> columns
) {

    @JsonClassDescription("One marimekko column with stacked segments summing to 100% of column height.")
    public record MarimekkoColumn(

        @JsonPropertyDescription("Unique column identifier. Also the default visible label.")
        @JsonProperty(required = true)
        String key,

        @JsonPropertyDescription("Proportional column width. Normalized across the row on render.")
        @JsonProperty(required = true)
        double width,

        @JsonPropertyDescription("Stacked segments, bottom-to-top. Values normalized to column height.")
        @JsonProperty(required = true)
        List<Segment> segments
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("One stacked segment inside a marimekko column.")
    public record Segment(

        @JsonPropertyDescription("Segment name rendered in the legend / tooltip.")
        @JsonProperty(required = true)
        String name,

        @JsonPropertyDescription("Segment value. Must be finite and non-negative.")
        @JsonProperty(required = true)
        double value,

        @JsonPropertyDescription("Optional color role for this segment.")
        @Nullable String color
    ) {}
}
