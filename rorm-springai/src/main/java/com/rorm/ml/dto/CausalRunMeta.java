package com.rorm.ml.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.jspecify.annotations.Nullable;

import java.util.Map;

@JsonNaming(SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record CausalRunMeta(
    String status,
    @Nullable String startedAt,
    @Nullable String completedAt,
    @Nullable Map<String, Object> spec,
    @Nullable CausalPipelineResult result
) {}
