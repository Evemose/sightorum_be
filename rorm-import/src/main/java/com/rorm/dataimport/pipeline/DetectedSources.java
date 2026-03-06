package com.rorm.dataimport.pipeline;

import com.rorm.dataimport.source.ImportDataSource;
import com.rorm.dataimport.type.InvalidValueCoercionStrategy;

import java.util.List;
import java.util.Map;

public record DetectedSources(
    ImportPipelineRequest request,
    List<ImportDataSource> dataSources,
    DetectedSchema schema,
    Map<ImportRequest.AttributeKey, InvalidValueCoercionStrategy> coercions
) {}
