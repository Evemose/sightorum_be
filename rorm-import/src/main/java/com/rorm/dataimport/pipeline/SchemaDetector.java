package com.rorm.dataimport.pipeline;

import com.rorm.dataimport.attribute.AttributeTypeDetector;
import com.rorm.dataimport.attribute.DetectedAttribute;
import com.rorm.dataimport.naming.NamingStyle;
import com.rorm.dataimport.naming.NamingStyleDetector;
import com.rorm.dataimport.override.SchemaOverride;
import com.rorm.dataimport.source.ImportDataSource;
import com.rorm.dataimport.type.DataTypeDetector;
import com.rorm.dataimport.type.NullCoalescingStrategy;
import com.rorm.metamodel.DataType;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Detects schema structure from data sources: attributes, types, and overrides.
 */
@RequiredArgsConstructor
class SchemaDetector {

    private final NamingStyleDetector namingStyleDetector;
    private final DataTypeDetector typeDetector;

    /**
     * Detects schema information from data sources.
     * Returns detected attributes, column types, and extracted type overrides.
     *
     * @param dataSources list of data sources to process
     * @param overridesByRoot map of root name to overrides specific to that root
     * @param defaultListSeparator default separator for collection attributes
     */
    public DetectedSchema detectSchema(
        List<ImportDataSource> dataSources,
        Map<String, List<SchemaOverride>> overridesByRoot,
        String defaultListSeparator
    ) {
        var rootNames = dataSources.stream()
            .map(ImportDataSource::getRootName)
            .collect(Collectors.toSet());

        var detectedRoots = new HashMap<String, DetectedRoot>();

        for (var dataSource : dataSources) {
            var rootName = dataSource.getRootName();
            var columnDataTypes = detectColumnDataTypesForRoot(dataSource);
            var rootOverrides = overridesByRoot.getOrDefault(rootName, List.of());
            applyTypeOverridesRecursive(columnDataTypes, rootOverrides);
            var attributes = detectAttributesForRoot(dataSource, rootNames, rootOverrides, defaultListSeparator);
            enrichAttributesWithTypes(attributes, columnDataTypes);
            var idColumn = detectIdColumnForRoot(dataSource, rootOverrides, columnDataTypes);

            detectedRoots.put(rootName, new DetectedRoot(
                rootName,
                attributes,
                idColumn
            ));
        }

        // Handle one-to-one roots - pass all overrides since OneToOne roots might be defined in any root's overrides
        var allOverrides = overridesByRoot.values().stream()
            .flatMap(List::stream)
            .toList();
        var oneToOneRoots = collectAndCreateOneToOneRoots(detectedRoots, allOverrides);
        detectedRoots.putAll(oneToOneRoots);

        return new DetectedSchema(detectedRoots);
    }


    private Map<String, DataType> detectColumnDataTypesForRoot(ImportDataSource dataSource) {
        var columnDataTypes = new HashMap<String, DataType>();
        var columnNames = dataSource.getColumnNames();
        var nullStrategy = NullCoalescingStrategy.SkipNulls.INSTANCE;

        var columnSamples = new HashMap<String, List<String>>();
        for (var columnName : columnNames) {
            columnSamples.put(columnName, new ArrayList<>());
        }

        try (var stream = dataSource.stream()) {
            stream.limit(100).forEach(row -> {
                for (var columnName : columnNames) {
                    columnSamples.get(columnName).add(row.get(columnName));
                }
            });
        }

        for (var columnName : columnNames) {
            var samples = columnSamples.get(columnName);
            var dataType = typeDetector.detectType(samples, nullStrategy);
            columnDataTypes.put(columnName, dataType);
        }

        return columnDataTypes;
    }


    private void applyTypeOverridesRecursive(Map<String, DataType> columnDataTypes, List<SchemaOverride> overrides) {
        // Root-level overrides search in all columns
        applyTypeOverrides(columnDataTypes, new ArrayList<>(columnDataTypes.keySet()), overrides, "");
    }

    private void applyTypeOverrides(
        Map<String, DataType> columnDataTypes,
        List<String> searchColumns,
        List<SchemaOverride> overrides,
        String parentPrefix
    ) {
        for (var override : overrides) {
            switch (override) {
                case SchemaOverride.BasicAttributeOverride basic when basic.dataType() != null ->
                    applyBasicAttributeOverride(columnDataTypes, searchColumns, basic, parentPrefix);
                case SchemaOverride.CollectionAttributeOverride coll when coll.elementType() != null ->
                    applyCollectionAttributeOverride(columnDataTypes, searchColumns, coll, parentPrefix);
                case SchemaOverride.CompositeAttributeOverride composite when !composite.nestedOverrides().isEmpty() ->
                    applyCompositeAttributeOverride(columnDataTypes, composite, parentPrefix);
                case SchemaOverride.OneToOneRootOverride oneToOne when !oneToOne.nestedOverrides().isEmpty() ->
                    applyOneToOneRootOverride(columnDataTypes, oneToOne, parentPrefix);
                default -> {
                }
            }
        }
    }

    private void applyBasicAttributeOverride(
        Map<String, DataType> columnDataTypes,
        List<String> searchColumns,
        SchemaOverride.BasicAttributeOverride basic,
        String parentPrefix
    ) {
        var columnName = findColumnForAttribute(
            basic.attributeName(),
            searchColumns,
            parentPrefix
        ).orElseThrow(() -> new IllegalArgumentException(
            "Cannot find column for attribute override: " + basic.attributeName() +
            (parentPrefix.isEmpty() ? "" : " under " + parentPrefix)
        ));
        columnDataTypes.put(columnName, Objects.requireNonNull(basic.dataType()));
    }

    private void applyCollectionAttributeOverride(
        Map<String, DataType> columnDataTypes,
        List<String> searchColumns,
        SchemaOverride.CollectionAttributeOverride coll,
        String parentPrefix
    ) {
        var columnName = findColumnForAttribute(
            coll.attributeName(),
            searchColumns,
            parentPrefix
        ).orElseThrow(() -> new IllegalArgumentException(
            "Cannot find column for collection attribute override: " + coll.attributeName() +
            (parentPrefix.isEmpty() ? "" : " under " + parentPrefix)
        ));
        columnDataTypes.put(columnName, Objects.requireNonNull(coll.elementType()));
    }

    private void applyCompositeAttributeOverride(
        Map<String, DataType> columnDataTypes,
        SchemaOverride.CompositeAttributeOverride composite,
        String parentPrefix
    ) {
        // For nested overrides, search only within the composite's sub-columns
        var namingStyle = namingStyleDetector.detect(composite.subAttributeColumns());
        var compositePrefix = buildNestedPrefix(parentPrefix, composite.attributeName(), namingStyle);
        applyTypeOverrides(
            columnDataTypes,
            composite.subAttributeColumns(),
            composite.nestedOverrides(),
            compositePrefix
        );
    }

    private void applyOneToOneRootOverride(
        Map<String, DataType> columnDataTypes,
        SchemaOverride.OneToOneRootOverride oneToOne,
        String parentPrefix
    ) {
        // Similar to composite, but with OneToOne's sub-columns
        var namingStyle = namingStyleDetector.detect(oneToOne.subAttributeColumns());
        var oneToOnePrefix = buildNestedPrefix(parentPrefix, oneToOne.attributeName(), namingStyle);
        applyTypeOverrides(
            columnDataTypes,
            oneToOne.subAttributeColumns(),
            oneToOne.nestedOverrides(),
            oneToOnePrefix
        );
    }

    private String buildNestedPrefix(String parentPrefix, String attributeName, NamingStyle namingStyle) {
        // Convert attribute name to column format using naming style
        var columnPrefix = convertAttributeNameToColumn(attributeName, namingStyle);
        return parentPrefix.isEmpty() ?
            columnPrefix :
            parentPrefix + namingStyle.getSeparator() + columnPrefix;
    }

    private String convertAttributeNameToColumn(String attributeName, NamingStyle namingStyle) {
        // Convert from camelCase attribute name to the target naming style
        // Split by case changes (e.g., "homeAddress" -> ["home", "Address"])
        var parts = attributeName.split("(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");
        // Join using the naming style's rules
        return namingStyle.join(parts);
    }

    private Optional<String> findColumnForAttribute(
        String attributeName,
        List<String> searchColumns,
        String parentPrefix
    ) {
        // Detect naming style from available columns to properly convert attribute name
        var namingStyle = namingStyleDetector.detect(searchColumns);
        var columnPrefix = convertAttributeNameToColumn(attributeName, namingStyle);

        // If we have a parent prefix, the column should be parentPrefix + separator + columnPrefix
        var expectedColumn = parentPrefix.isEmpty() ?
            columnPrefix :
            parentPrefix + namingStyle.getSeparator() + columnPrefix;

        // Look for exact match first
        if (searchColumns.contains(expectedColumn)) {
            return Optional.of(expectedColumn);
        }

        // If no exact match, try case-insensitive matching
        return searchColumns.stream()
            .filter(col -> col.equalsIgnoreCase(expectedColumn))
            .findFirst();
    }

    private void enrichAttributesWithTypes(Map<String, DetectedAttribute> attributes, Map<String, DataType> columnDataTypes) {
        var enrichedAttributes = new HashMap<String, DetectedAttribute>();
        for (var entry : attributes.entrySet()) {
            var name = entry.getKey();
            var attr = entry.getValue();
            enrichedAttributes.put(name, enrichAttributeWithType(attr, columnDataTypes));
        }
        attributes.clear();
        attributes.putAll(enrichedAttributes);
    }

    private DetectedAttribute enrichAttributeWithType(DetectedAttribute attr, Map<String, DataType> columnDataTypes) {
        return switch (attr) {
            case DetectedAttribute.Basic basic -> new DetectedAttribute.Basic(basic.name(), basic.columnName(),
                columnDataTypes.get(basic.columnName()));
            case DetectedAttribute.Collection coll ->
                new DetectedAttribute.Collection(coll.name(), coll.columnName(), coll.separator(),
                    columnDataTypes.get(coll.columnName()));
            case DetectedAttribute.Composite comp -> new DetectedAttribute.Composite(comp.name(),
                enrichSubAttributesWithTypes(comp.subAttributes(), columnDataTypes));
            case DetectedAttribute.OneToOneRoot oneToOne ->
                new DetectedAttribute.OneToOneRoot(oneToOne.name(), oneToOne.targetRootName(),
                    enrichSubAttributesWithTypes(oneToOne.subAttributes(), columnDataTypes));
            default -> attr;
        };
    }

    private Map<String, DetectedAttribute> enrichSubAttributesWithTypes(
        Map<String, DetectedAttribute> subAttributes,
        Map<String, DataType> columnDataTypes
    ) {
        var enriched = new HashMap<String, DetectedAttribute>();
        for (var entry : subAttributes.entrySet()) {
            enriched.put(entry.getKey(), enrichAttributeWithType(entry.getValue(), columnDataTypes));
        }
        return enriched;
    }

    private Map<String, DetectedAttribute> detectAttributesForRoot(
        ImportDataSource dataSource,
        Set<String> rootNames,
        List<SchemaOverride> overrides,
        String defaultListSeparator
    ) {
        var columnNames = new ArrayList<>(dataSource.getColumnNames());

        // Exclude ID column from regular attribute detection if there's an IdAttributeOverride
        overrides.stream()
            .filter(o -> o instanceof SchemaOverride.IdAttributeOverride)
            .map(o -> (SchemaOverride.IdAttributeOverride) o)
            .filter(o -> o.attributeName().equals("id"))
            .findFirst()
            .ifPresent(idAttributeOverride ->
                columnNames.remove(idAttributeOverride.columnName())
            );

        var namingStyle = namingStyleDetector.detect(columnNames);

        var detector = new AttributeTypeDetector(
            rootNames,
            namingStyle,
            overrides,
            defaultListSeparator
        );

        return detector.detectAttributes(columnNames);
    }

    private Map<String, DetectedRoot> collectAndCreateOneToOneRoots(
        Map<String, DetectedRoot> detectedRoots,
        List<SchemaOverride> overrides
    ) {
        var oneToOneRoots = new HashMap<String, DetectedRoot>();
        for (var root : detectedRoots.values()) {
            collectOneToOneRootsRecursive(root.attributes().values(), oneToOneRoots, overrides);
        }
        return oneToOneRoots;
    }

    private void collectOneToOneRootsRecursive(
        Collection<DetectedAttribute> attributes,
        Map<String, DetectedRoot> oneToOneRoots,
        List<SchemaOverride> overrides
    ) {
        for (var attr : attributes) {
            switch (attr) {
                case DetectedAttribute.Composite composite ->
                    collectOneToOneRootsRecursive(composite.subAttributes().values(), oneToOneRoots, overrides);
                case DetectedAttribute.OneToOneRoot oneToOne -> {
                    var rootName = oneToOne.targetRootName();
                    if (!oneToOneRoots.containsKey(rootName)) {
                        // Find the override for this OneToOneRoot to get its idColumn
                        var idColumnOverride = findOneToOneIdColumnOverride(rootName, overrides);
                        var idColumn = detectIdColumnForOneToOneRoot(oneToOne.subAttributes(), idColumnOverride);

                        oneToOneRoots.put(rootName, new DetectedRoot(
                            rootName,
                            oneToOne.subAttributes(),
                            idColumn
                        ));
                    }
                }
                default -> {
                }
            }
        }
    }

    private @Nullable String findOneToOneIdColumnOverride(String targetRootName, List<SchemaOverride> overrides) {
        for (var override : overrides) {
            switch (override) {
                case SchemaOverride.OneToOneRootOverride oneToOne -> {
                    if (oneToOne.targetRootName().equals(targetRootName) && oneToOne.idColumn() != null) {
                        return oneToOne.idColumn();
                    }
                    // Recursively search in nested overrides
                    var nested = findOneToOneIdColumnOverride(targetRootName, oneToOne.nestedOverrides());
                    if (nested != null) {
                        return nested;
                    }
                }
                case SchemaOverride.CompositeAttributeOverride composite -> {
                    // Recursively search in nested overrides
                    var nested = findOneToOneIdColumnOverride(targetRootName, composite.nestedOverrides());
                    if (nested != null) {
                        return nested;
                    }
                }
                default -> {
                }
            }
        }
        return null;
    }

    private DetectedIdColumn detectIdColumnForOneToOneRoot(
        Map<String, DetectedAttribute> subAttributes,
        @Nullable String overriddenIdColumn
    ) {
        // First, try to use the explicitly overridden ID column
        if (overriddenIdColumn != null) {
            var overriddenAttr = subAttributes.values().stream()
                .filter(attr -> attr instanceof DetectedAttribute.Basic)
                .map(attr -> (DetectedAttribute.Basic) attr)
                .filter(basic -> basic.columnName().equalsIgnoreCase(overriddenIdColumn))
                .findFirst()
                .orElse(null);

            if (overriddenAttr != null) {
                var dataType = Objects.requireNonNullElseGet(
                    overriddenAttr.dataType(),
                    () -> new DataType.NumericType(19, 0)
                );
                return new DetectedIdColumn(overriddenAttr.name(), overriddenAttr.columnName(), dataType);
            }
        }

        // Look for a column ending with _id (the prefix_id pattern)
        var idAttr = subAttributes.values().stream()
            .filter(attr -> attr instanceof DetectedAttribute.Basic)
            .map(attr -> (DetectedAttribute.Basic) attr)
            .filter(basic -> basic.columnName().toLowerCase().endsWith("_id"))
            .findFirst()
            .orElse(null);

        if (idAttr != null) {
            var dataType = Objects.requireNonNullElseGet(
                idAttr.dataType(),
                () -> new DataType.NumericType(19, 0)
            );
            // Use the attribute name as the ID attribute name
            return new DetectedIdColumn(idAttr.name(), idAttr.columnName(), dataType);
        }

        // Default fallback
        return new DetectedIdColumn("id", "id", new DataType.NumericType(19, 0));
    }

    private DetectedIdColumn detectIdColumnForRoot(
        ImportDataSource dataSource,
        List<SchemaOverride> overrides,
        Map<String, DataType> columnDataTypes
    ) {
        var idOverride = overrides.stream()
            .filter(o -> o instanceof SchemaOverride.IdAttributeOverride)
            .map(o -> (SchemaOverride.IdAttributeOverride) o)
            .filter(o -> o.attributeName().equals("id"))
            .findFirst();

        if (idOverride.isPresent()) {
            var override = idOverride.get();
            var dataType = override.dataType();
            if (dataType == null) {
                // No explicit type in override, use detected type or default to NumericType
                dataType = columnDataTypes.getOrDefault(override.columnName(), new DataType.NumericType(19, 0));
            }
            return new DetectedIdColumn("id", override.columnName(), dataType);
        } else {
            var idColumn = dataSource.getColumnNames().stream()
                .filter(c -> c.equalsIgnoreCase("id"))
                .findFirst()
                .orElse("id");
            var dataType = columnDataTypes.getOrDefault(idColumn, new DataType.NumericType(19, 0));
            return new DetectedIdColumn("id", idColumn, dataType);
        }
    }

    public record DetectedSchema(
        Map<String, DetectedRoot> roots
    ) {
    }

    public record DetectedRoot(
        String name,
        Map<String, DetectedAttribute> attributes,
        DetectedIdColumn idColumn
    ) {
    }

    public record DetectedIdColumn(
        String attributeName,
        String columnName,
        DataType dataType
    ) {
    }
}
