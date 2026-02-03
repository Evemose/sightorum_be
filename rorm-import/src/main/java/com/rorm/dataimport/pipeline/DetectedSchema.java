package com.rorm.dataimport.pipeline;

import com.rorm.dataimport.pipeline.SchemaDetector.DetectedRoot;

import java.util.Map;

public record DetectedSchema(
    Map<String, DetectedRoot> roots
) {
}
