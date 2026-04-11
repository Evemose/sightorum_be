package com.rorm.ml.dto.pipelinespec;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(SnakeCaseStrategy.class)
@JsonClassDescription("""
    Mediation analysis for a single mediator, referencing two estimation
    variants: a 'total' variant (mediator excluded from W) and a 'direct'
    variant (mediator included in W). The indirect effect is total - direct.""")
public record MediationConfig(

    @JsonPropertyDescription("Mediator column name. Must be in the query SELECT.")
    @JsonProperty(required = true)
    String mediator,

    @JsonPropertyDescription("Narrative description of the treatment -> mediator -> outcome pathway.")
    @JsonProperty(required = true)
    String pathway,

    @JsonPropertyDescription("Id of the estimation variant that excludes the mediator from W (total effect).")
    @JsonProperty(required = true)
    String totalVariantId,

    @JsonPropertyDescription("Id of the estimation variant that includes the mediator in W (direct effect).")
    @JsonProperty(required = true)
    String directVariantId
) {}
