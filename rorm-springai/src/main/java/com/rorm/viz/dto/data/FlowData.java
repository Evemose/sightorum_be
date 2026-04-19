package com.rorm.viz.dto.data;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

@JsonClassDescription("""
    Sankey flow payload.
    
    Invariant:
    Every `FlowLink.source` and `FlowLink.target` MUST match an existing
    `FlowNode.id`. The backend is responsible for this referential
    integrity check.""")
public record FlowData(

    @JsonPropertyDescription("All nodes in the flow graph, in render order.")
    @JsonProperty(required = true)
    List<FlowNode> nodes,

    @JsonPropertyDescription("All links in the flow graph.")
    @JsonProperty(required = true)
    List<FlowLink> links
) {

    @JsonClassDescription("One node in a flow graph.")
    public record FlowNode(

        @JsonPropertyDescription("Unique node identifier referenced by FlowLink.source / target.")
        @JsonProperty(required = true)
        String id,

        @JsonPropertyDescription("Display name rendered next to the node.")
        @JsonProperty(required = true)
        String name
    ) {}

    @JsonClassDescription("One link between two flow nodes. Weighted by `value`.")
    public record FlowLink(

        @JsonPropertyDescription("Source node id. MUST match some FlowNode.id.")
        @JsonProperty(required = true)
        String source,

        @JsonPropertyDescription("Target node id. MUST match some FlowNode.id.")
        @JsonProperty(required = true)
        String target,

        @JsonPropertyDescription("Flow magnitude. Must be finite and non-negative.")
        @JsonProperty(required = true)
        double value
    ) {}
}
