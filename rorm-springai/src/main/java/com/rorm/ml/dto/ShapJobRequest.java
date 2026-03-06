package com.rorm.ml.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.jspecify.annotations.Nullable;

import java.util.List;

@JsonNaming(SnakeCaseStrategy.class)
public record ShapJobRequest(
    @JsonIgnore String reason,
    String runId,
    @Nullable List<String> features,
    int nBins,
    int nBreakpoints
) {}
