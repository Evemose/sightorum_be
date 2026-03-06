package com.rorm.dataimport.pipeline;

import com.rorm.dataimport.override.DetectionOverride;
import com.rorm.dataimport.type.InvalidValueCoercionStrategy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public record ImportPipelineRequest(
    Path uploadDir,
    String targetSchema,
    int chunkSize,
    String listSeparator,
    Map<String, List<DetectionOverride>> overridesByRoot,
    Map<ImportRequest.AttributeKey, InvalidValueCoercionStrategy> coercionStrategies
) {}
