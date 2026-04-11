package com.rorm.ml.dto.pipelinespec;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(SnakeCaseStrategy.class)
@JsonClassDescription("""
    A variable excluded from the primary W matrix because it lies on the
    directed path from treatment to outcome. Points at a companion variant
    that re-includes the mediator in W for direct-effect estimation.""")
public record MediatorExclusion(

    @JsonPropertyDescription("Column name of the excluded mediator. Must be in the query SELECT and not stripped.")
    @JsonProperty(required = true)
    String column,

    @JsonPropertyDescription("Narrative description of the mediation pathway from treatment to outcome.")
    @JsonProperty(required = true)
    String pathway,

    @JsonPropertyDescription("Id of the variant that re-includes the mediator in W for the direct-effect estimate.")
    @JsonProperty(required = true)
    String directEffectVariantId
) {}
