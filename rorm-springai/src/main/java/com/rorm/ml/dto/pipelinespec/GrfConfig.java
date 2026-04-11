package com.rorm.ml.dto.pipelinespec;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;
import java.util.Map;

@JsonNaming(SnakeCaseStrategy.class)
@JsonClassDescription("""
    Generalized Random Forest configuration for heterogeneous-effect
    estimation. Each config specifies candidate effect-modifier columns and
    a slicing strategy per modifier.""")
public record GrfConfig(

    @JsonPropertyDescription("Unique id for this GRF config within the spec.")
    @JsonProperty(required = true)
    String id,

    @JsonPropertyDescription("""
        Candidate effect-modifier columns. Each column must exist in the
        query SELECT and may be numeric or categorical (categoricals are
        label-encoded automatically before GRF fitting).""")
    @JsonProperty(required = true)
    List<String> modifierColumns,

    @JsonPropertyDescription("""
        Slicing strategy per modifier column. Each map key is a column
        name from modifierColumns; the value is exactly UNIQUE (for
        categoricals with fewer than 20 levels) or QUARTILE (for continuous
        variables).""")
    @JsonProperty(required = true)
    Map<String, SlicingMethod> slicing
) {

    @JsonClassDescription("""
        Slicing strategy for a GRF modifier column. UNIQUE enumerates each
        distinct value as its own slice (for categoricals with fewer than
        20 levels); QUARTILE splits the continuous distribution into four
        equal-count bins.""")
    public enum SlicingMethod {
        @JsonProperty("unique") @JsonAlias("UNIQUE") UNIQUE,
        @JsonProperty("quartile") @JsonAlias("QUARTILE") QUARTILE
    }
}
