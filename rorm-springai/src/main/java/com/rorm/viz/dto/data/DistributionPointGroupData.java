package com.rorm.viz.dto.data;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.jspecify.annotations.Nullable;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonClassDescription("""
    Payload for beeswarm / strip plots: individual points distributed
    within a group along an orthogonal jitter axis.""")
public record DistributionPointGroupData(

    @JsonPropertyDescription("Groups plotted side-by-side along the group axis.")
    @JsonProperty(required = true)
    List<Group> groups
) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("One group's jitter-cloud of individual points.")
    public record Group(

        @JsonPropertyDescription("Group name rendered on the category axis.")
        @JsonProperty(required = true)
        String name,

        @JsonPropertyDescription("Optional per-group default color role.")
        @Nullable String color,

        @JsonPropertyDescription("Individual points belonging to this group.")
        @JsonProperty(required = true)
        List<DistributionPoint> points
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("One point in a beeswarm / strip plot.")
    public record DistributionPoint(

        @JsonPropertyDescription("Measured value along the value axis.")
        @JsonProperty(required = true)
        double value,

        @JsonPropertyDescription("""
            Optional horizontal jitter in [-0.2, 0.2] relative to the
            group center. Used to spread overlapping points.""")
        @Nullable Double offset,

        @JsonPropertyDescription("Optional per-point size.")
        @Nullable Double size,

        @JsonPropertyDescription("Optional per-point color role overriding the group color.")
        @Nullable String color
    ) {}
}
