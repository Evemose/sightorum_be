package com.rorm.dataimport.pipeline;

import com.rorm.dataimport.override.SchemaOverride;
import com.rorm.dataimport.source.ImportDataSource;
import com.rorm.metamodel.ModelSpace;
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
    private final MetamodelConverter metamodelConverter;

    public ModelSpace detectModelSpace(
        List<ImportDataSource> dataSources,
        Map<String, List<SchemaOverride>> overridesByRoot,
        String defaultListSeparator
    ) {
        var detectedSchema = schemaDetector.detectSchema(dataSources, overridesByRoot, defaultListSeparator);
        return metamodelConverter.convertToModelSpace(detectedSchema);
    }
}
