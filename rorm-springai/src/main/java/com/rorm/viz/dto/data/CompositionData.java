package com.rorm.viz.dto.data;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.jspecify.annotations.Nullable;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonClassDescription("""
    Composition payload for pie and donut charts.
    
    Invariants (backend MUST enforce):
    - `share` is in [0, 1] and is pre-computed by the backend
      (value / total). The frontend never recomputes shares.
    - sum(parts.value) + (other?.value ?? 0) == total, within rounding.""")
public record CompositionData(

    @JsonPropertyDescription("Sum of all parts (and tail). Used as the denominator for `share`.")
    @JsonProperty(required = true)
    double total,

    @JsonPropertyDescription("Main slices in render order.")
    @JsonProperty(required = true)
    List<CompositionPart> parts,

    @JsonPropertyDescription("""
        Optional tail aggregation bundling all remaining slices under an
        'Other' group. `other.value + sum(parts.value) == total`.""")
    @Nullable Other other
) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("One pie / donut slice.")
    public record CompositionPart(

        @JsonPropertyDescription("Unique slice identifier. Also the default visible label.")
        @JsonProperty(required = true)
        String key,

        @JsonPropertyDescription("Raw value for this slice. Must be finite and non-negative.")
        @JsonProperty(required = true)
        double value,

        @JsonPropertyDescription("Pre-computed fraction of the total, in [0, 1].")
        @JsonProperty(required = true)
        double share,

        @JsonPropertyDescription("Optional per-slice color role.")
        @Nullable String color
    ) {}

    @JsonClassDescription("Tail aggregation. `value` + `share` for all slices rolled under 'Other'.")
    public record Other(

        @JsonPropertyDescription("Combined value of all tail slices.")
        @JsonProperty(required = true)
        double value,

        @JsonPropertyDescription("Combined share of all tail slices, in [0, 1].")
        @JsonProperty(required = true)
        double share
    ) {}
}
