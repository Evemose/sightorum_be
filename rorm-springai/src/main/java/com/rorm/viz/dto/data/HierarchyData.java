package com.rorm.viz.dto.data;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.jspecify.annotations.Nullable;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonClassDescription("""
    Hierarchy payload consumed by treemap, sunburst, and dendrogram
    charts. A single root node carries a recursive tree of children.
    
    Invariant:
    `value` is emitted only on LEAF nodes. Internal nodes derive their
    weight from the sum of descendants.""")
public record HierarchyData(

    @JsonPropertyDescription("Root node of the hierarchy.")
    @JsonProperty(required = true)
    HierarchyNode root
) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("""
        One hierarchy node. A node is a LEAF when `children` is null or
        empty; leaves MUST supply `value`. Internal nodes MUST omit
        `value`; their weight is the sum of descendants.""")
    public record HierarchyNode(

        @JsonPropertyDescription("Node display name.")
        @JsonProperty(required = true)
        String name,

        @JsonPropertyDescription("""
            Numeric weight. REQUIRED on leaves, OMITTED on internal
            nodes. Must be finite and non-negative.""")
        @Nullable Double value,

        @JsonPropertyDescription("Optional node color role.")
        @Nullable String color,

        @JsonPropertyDescription("Child nodes. Null or empty when this node is a leaf.")
        @Nullable List<HierarchyNode> children
    ) {}
}
