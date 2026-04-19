package com.rorm.viz.dto.data;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

@JsonClassDescription("Stacked multi-year calendar heatmap payload. Each element is a self-contained single-year heatmap.")
public record CalendarMultiYearData(

    @JsonPropertyDescription("Years stacked vertically in the given order.")
    @JsonProperty(required = true)
    List<CalendarHeatmapData> years
) {}
