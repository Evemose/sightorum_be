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
    Split-violin payload: two side-by-side density curves per category.
    
    Invariants:
    - `series` is a 2-tuple: exactly two groups. Backend MUST reject
      upstream when only one series is available.
    - Each group's `densityByCategory` has `categories.length` curves,
      in the same order as `categories`. Each curve is a list of
      [value, density] pairs.""")
public record SplitViolinData(

    @JsonPropertyDescription("Category axis labels, shared by both series.")
    @JsonProperty(required = true)
    List<String> categories,

    @JsonPropertyDescription("Exactly two split-violin groups rendered side-by-side per category.")
    @JsonProperty(required = true)
    List<SplitViolinGroup> series
) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("""
        One half of a split violin. `densityByCategory[i]` is the density
        curve for `categories[i]`, given as a list of [value, density]
        pairs.""")
    public record SplitViolinGroup(

        @JsonPropertyDescription("Group name rendered in the legend.")
        @JsonProperty(required = true)
        String name,

        @JsonPropertyDescription("""
            Per-category density curves. Length MUST equal
            categories.length. Each inner list is a series of
            [value, density] pairs.""")
        @JsonProperty(required = true)
        List<List<List<Double>>> densityByCategory,

        @JsonPropertyDescription("Optional color role for this half.")
        @Nullable ColorRole color
    ) {}
}
