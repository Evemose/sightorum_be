package com.rorm.dataimport.pipeline;

import com.rorm.dataimport.attribute.AttributeTypeDetector;
import com.rorm.dataimport.attribute.DetectedAttribute;
import com.rorm.dataimport.naming.NamingStyleDetector;
import com.rorm.dataimport.override.SchemaOverride;
import com.rorm.dataimport.source.ImportDataSource;
import com.rorm.dataimport.type.DataTypeDetector;
import com.rorm.dataimport.type.NullCoalescingStrategy;
import com.rorm.metamodel.DataType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Detects schema structure from data sources: attributes, types, and overrides.
 */
@Component
@RequiredArgsConstructor
class SchemaDetector {

    private final NamingStyleDetector namingStyleDetector;
    private final DataTypeDetector typeDetector;

    /**
     * Detects schema information from data sources.
     * Returns detected attributes, column types, and extracted type overrides.
     */
    public DetectedSchema detectSchema(
        List<ImportDataSource> dataSources,
        List<SchemaOverride> overrides,
        String defaultListSeparator
    ) {
        var rootNames = dataSources.stream()
            .map(ImportDataSource::getRootName)
            .collect(Collectors.toSet());

        // Detect column data types from actual data
        var columnDataTypes = detectColumnDataTypes(dataSources);

        // Extract explicit type overrides from schema overrides
        var typeOverrides = extractTypeOverrides(overrides);

        // Detect attributes for each data source
        var detectedRoots = detectAttributesFromDataSources(
            dataSources,
            rootNames,
            overrides,
            defaultListSeparator
        );

        // Collect OneToOneRoot definitions
        var oneToOneRoots = collectOneToOneRoots(detectedRoots);

        // Combine all roots
        var allDetectedRootDescriptions = new HashMap<String, Map<String, DetectedAttribute>>();
        allDetectedRootDescriptions.putAll(detectedRoots);
        allDetectedRootDescriptions.putAll(oneToOneRoots);

        return new DetectedSchema(
            allDetectedRootDescriptions,
            columnDataTypes,
            typeOverrides
        );
    }

    private Map<String, Map<String, DataType>> detectColumnDataTypes(List<ImportDataSource> dataSources) {
        var result = new HashMap<String, Map<String, DataType>>();
        var nullStrategy = NullCoalescingStrategy.SkipNulls.INSTANCE;

        for (var dataSource : dataSources) {
            var columnDataTypes = new HashMap<String, DataType>();
            var columnNames = dataSource.getColumnNames();

            // Build collections for each column
            var columnSamples = new HashMap<String, List<String>>();
            for (var columnName : columnNames) {
                columnSamples.put(columnName, new ArrayList<>());
            }

            // Sample data from the stream
            try (var stream = dataSource.stream()) {
                stream.limit(100).forEach(row -> {
                    for (var columnName : columnNames) {
                        columnSamples.get(columnName).add(row.get(columnName));
                    }
                });
            }

            // Detect type for each column
            for (var columnName : columnNames) {
                var samples = columnSamples.get(columnName);
                var dataType = typeDetector.detectType(samples, nullStrategy);
                columnDataTypes.put(columnName, dataType);
            }

            result.put(dataSource.getRootName(), columnDataTypes);
        }

        return result;
    }

    private Map<String, DataType> extractTypeOverrides(List<SchemaOverride> overrides) {
        var typeOverrides = new HashMap<String, DataType>();
        extractTypeOverridesRecursive(overrides, typeOverrides);
        return typeOverrides;
    }

    private void extractTypeOverridesRecursive(List<SchemaOverride> overrides, Map<String, DataType> typeOverrides) {
        for (var override : overrides) {
            switch (override) {
                case SchemaOverride.BasicAttributeOverride basic when basic.dataType() != null ->
                    typeOverrides.put(basic.attributeName(), basic.dataType());
                case SchemaOverride.CollectionAttributeOverride coll when coll.elementType() != null ->
                    typeOverrides.put(coll.attributeName(), coll.elementType());
                case SchemaOverride.CompositeAttributeOverride composite when !composite.nestedOverrides().isEmpty() ->
                    extractTypeOverridesRecursive(composite.nestedOverrides(), typeOverrides);
                case SchemaOverride.OneToOneRootOverride oneToOne when !oneToOne.nestedOverrides().isEmpty() ->
                    extractTypeOverridesRecursive(oneToOne.nestedOverrides(), typeOverrides);
                default -> {
                    // No type overrides for reference attributes
                }
            }
        }
    }

    private Map<String, Map<String, DetectedAttribute>> detectAttributesFromDataSources(
        List<ImportDataSource> dataSources,
        Set<String> rootNames,
        List<SchemaOverride> overrides,
        String defaultListSeparator
    ) {
        var detectedRoots = new HashMap<String, Map<String, DetectedAttribute>>();
        for (var dataSource : dataSources) {
            var columnNames = dataSource.getColumnNames();
            var namingStyle = namingStyleDetector.detect(columnNames);

            var detector = new AttributeTypeDetector(
                rootNames,
                namingStyle,
                overrides,
                defaultListSeparator
            );

            var detectedAttributes = detector.detectAttributes(columnNames);
            detectedRoots.put(dataSource.getRootName(), detectedAttributes);
        }
        return detectedRoots;
    }

    private Map<String, Map<String, DetectedAttribute>> collectOneToOneRoots(
        Map<String, Map<String, DetectedAttribute>> detectedRoots
    ) {
        var oneToOneRoots = new HashMap<String, Map<String, DetectedAttribute>>();
        for (var detectedAttributes : detectedRoots.values()) {
            collectOneToOneRootsRecursive(detectedAttributes.values(), oneToOneRoots);
        }
        return oneToOneRoots;
    }

    private void collectOneToOneRootsRecursive(
        Collection<DetectedAttribute> attributes,
        Map<String, Map<String, DetectedAttribute>> oneToOneRoots
    ) {
        for (var attr : attributes) {
            switch (attr) {
                case DetectedAttribute.Composite composite ->
                    collectOneToOneRootsRecursive(composite.subAttributes().values(), oneToOneRoots);
                case DetectedAttribute.OneToOneRoot oneToOne -> {
                    oneToOneRoots.put(oneToOne.targetRootName(), oneToOne.subAttributes());
                    collectOneToOneRootsRecursive(oneToOne.subAttributes().values(), oneToOneRoots);
                }
                default -> {
                }
            }
        }
    }

    /**
     * Contains all detected schema information.
     */
    public record DetectedSchema(
        Map<String, Map<String, DetectedAttribute>> detectedAttributes,
        Map<String, Map<String, DataType>> columnDataTypes,
        Map<String, DataType> typeOverrides
    ) {
    }
}
