package com.rorm.viz.dto.data;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.jspecify.annotations.Nullable;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonClassDescription("""
    Voronoi-treemap hierarchy. Same leaf-vs-internal rule as
    HierarchyData, but nodes carry an explicit `id` (stable across
    renders) and use `weight` instead of `value`.""")
public record VoronoiTreemapData(

    @JsonPropertyDescription("Root node of the hierarchy.")
    @JsonProperty(required = true)
    VoronoiTreemapNode root
) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("""
        One voronoi-treemap node. Leaves MUST supply `weight`; internal
        nodes MUST omit it and derive their weight from descendants.""")
    public record VoronoiTreemapNode(

        @JsonPropertyDescription("Unique identifier for this node. Stable across renders.")
        @JsonProperty(required = true)
        String id,

        @JsonPropertyDescription("""
            Positive weight. REQUIRED on leaves, OMITTED on internal
            nodes. Controls the cell area.""")
        @Nullable Double weight,

        @JsonPropertyDescription("Optional node color role.")
        @Nullable String color,

        @JsonPropertyDescription("Child nodes. Null or empty when this node is a leaf.")
        @Nullable List<VoronoiTreemapNode> children
    ) {}
}
