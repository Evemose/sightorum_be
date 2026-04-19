package com.rorm.viz.dto.data;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonClassDescription("""
    Payload for table-with-inline-bar and table-lens charts: a set of
    rows with a primary value driving the inline bar, plus arbitrary
    extra columns.""")
public record TableInlineBarData(

    @JsonPropertyDescription("Table rows in render order.")
    @JsonProperty(required = true)
    List<TableInlineBarRow> rows,

    @JsonPropertyDescription("""
        Optional explicit maximum used to normalize bar widths across
        rows. When omitted, the frontend uses max(rows[*].value).""")
    @Nullable Double maxValue
) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("One row of a table-with-inline-bar chart.")
    public record TableInlineBarRow(

        @JsonPropertyDescription("Unique row identifier. Also the default visible label.")
        @JsonProperty(required = true)
        String key,

        @JsonPropertyDescription("Primary value driving the inline bar length.")
        @JsonProperty(required = true)
        double value,

        @JsonPropertyDescription("""
            Optional extra row attributes displayed as additional table
            columns. Values are string or number scalars.""")
        @Nullable Map<String, Object> columns
    ) {}
}
