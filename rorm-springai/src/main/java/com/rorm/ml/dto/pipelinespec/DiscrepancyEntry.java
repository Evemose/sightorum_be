package com.rorm.ml.dto.pipelinespec;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(SnakeCaseStrategy.class)
@JsonClassDescription("""
    Discrepancy between a cited value and a recomputed value, recorded so
    the contradiction is visible for audit.""")
public record DiscrepancyEntry(

    @JsonPropertyDescription("Which field disagrees (free-form label).")
    @JsonProperty(required = true)
    String field,

    @JsonPropertyDescription("The originally cited value. Numeric values must carry units inline.")
    @JsonProperty(required = true)
    String generatorValue,

    @JsonPropertyDescription("The recomputed value. Numeric values must carry units inline.")
    @JsonProperty(required = true)
    String compilerValue,

    @JsonPropertyDescription("How the discrepancy was resolved (which side was adopted and why).")
    @JsonProperty(required = true)
    String resolution
) {}
