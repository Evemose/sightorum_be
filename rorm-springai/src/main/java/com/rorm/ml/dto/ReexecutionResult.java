package com.rorm.ml.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

@JsonNaming(SnakeCaseStrategy.class)
public record ReexecutionResult(
    @Nullable String runId,
    String parentRunId,
    List<String> changedSpecFields,
    List<String> reexecutedSteps,
    List<String> skippedSteps,
    Map<String, Object> diffs,
    Map<String, Object> result
) {}
