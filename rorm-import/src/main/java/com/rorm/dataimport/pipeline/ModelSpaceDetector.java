package com.rorm.dataimport.pipeline;

import com.rorm.dataimport.override.DetectionOverride;
import com.rorm.dataimport.source.ImportDataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Orchestrates schema detection and metamodel conversion.
 */
@Component
@RequiredArgsConstructor
public class ModelSpaceDetector {

    private final SchemaDetector schemaDetector;

    /**
     * Detects the model space and returns both the ModelSpace and DetectedSchema.
     * The DetectedSchema contains source mappings needed for multi-root imports.
     *
     * @param dataSources          Data sources to analyze (can be mixed flat and hierarchical)
     * @param overridesByRoot      Detection overrides organized by root name (SchemaOverride or HierarchicalOverride)
     * @param defaultListSeparator Default separator for list/collection attributes
     * @return Detection result containing both ModelSpace and DetectedSchema
     */
    public DetectedSchema detect(
        List<ImportDataSource> dataSources,
        Map<String, ? extends List<? extends DetectionOverride>> overridesByRoot,
        String defaultListSeparator
    ) {
        return schemaDetector.detectSchema(
            dataSources,
            overridesByRoot,
            defaultListSeparator
        );
    }
}
