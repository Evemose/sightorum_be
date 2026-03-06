package com.rorm.ml.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;
import java.util.Map;

@JsonNaming(SnakeCaseStrategy.class)
public record ShapCurvesResponse(
    String runId,
    String problemType,
    int nModels,
    int nBins,
    List<Map<String, Object>> curves
) {}
