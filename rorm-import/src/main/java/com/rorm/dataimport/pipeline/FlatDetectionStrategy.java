package com.rorm.dataimport.pipeline;

import com.rorm.dataimport.attribute.AttributeTypeDetector;
import com.rorm.dataimport.attribute.DetectedAttribute;
import com.rorm.dataimport.hierarchical.HierarchicalDataSource;
import com.rorm.dataimport.naming.NamingStyle;
import com.rorm.dataimport.naming.NamingStyleDetector;
import com.rorm.dataimport.override.DetectionOverride;
import com.rorm.dataimport.override.SchemaOverride;
import com.rorm.dataimport.pipeline.SchemaDetector.DetectedIdColumn;
import com.rorm.dataimport.pipeline.SchemaDetector.DetectedRoot;
import com.rorm.dataimport.source.ImportDataSource;
import com.rorm.dataimport.type.DataTypeDetector;
import com.rorm.dataimport.type.InMemoryCoercion;
import com.rorm.metamodel.DataType;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

import java.util.*;

/**
 * Detection strategy for flat (CSV/TSV) data sources.
 * <p>
 * Uses column-based heuristics to detect schema structure including:
 * - Basic attributes from simple columns
 * - Composite attributes from prefixed columns
 * - References from *_id columns matching known roots
 * - OneToOneRoot from *_id columns with additional prefixed columns
 */
@RequiredArgsConstructor
public class FlatDetectionStrategy implements DetectionStrategy<ImportDataSource> {

    private final NamingStyleDetector namingStyleDetector;
    private final DataTypeDetector typeDetector;

    @Override
    public boolean canHandle(ImportDataSource dataSource) {
        return !(dataSource instanceof HierarchicalDataSource);
    }

    @Override
    public DetectedSchema detect(
        List<ImportDataSource> dataSources,
        Map<String, ? extends List<? extends DetectionOverride>> overridesByRoot,
        String defaultListSeparator,
        Set<String> allRootNames
    ) {
        // Filter to only SchemaOverride types
        var schemaOverridesByRoot = filterSchemaOverrides(overridesByRoot);

        var detectedRoots = new HashMap<String, DetectedRoot>();

        for (var dataSource : dataSources) {
            var rootName = dataSource.getRootName();
            var dataSourceName = dataSource.getRootName();
            var columnDataTypes = detectColumnDataTypesForRoot(dataSource);
            var rootOverrides = schemaOverridesByRoot.getOrDefault(rootName, List.of());
            applyTypeOverridesRecursive(columnDataTypes, rootOverrides);
            // Use allRootNames for cross-source reference detection
            var attributes = detectAttributesForRoot(dataSource, allRootNames, rootOverrides, defaultListSeparator, dataSourceName, rootName);
            enrichAttributesWithTypes(attributes, columnDataTypes);
            var idColumn = detectIdColumnForRoot(dataSource, rootOverrides, columnDataTypes);

            detectedRoots.put(rootName, new DetectedRoot(
                rootName,
                dataSourceName,
                attributes,
                idColumn
            ));
        }

        // Handle one-to-one roots
        var allOverrides = schemaOverridesByRoot.values().stream()
            .flatMap(List::stream)
            .toList();
        var oneToOneRoots = collectAndCreateOneToOneRoots(detectedRoots, allOverrides);
        detectedRoots.putAll(oneToOneRoots);

        return new DetectedSchema(detectedRoots);
    }

    private Map<String, List<SchemaOverride>> filterSchemaOverrides(
        Map<String, ? extends List<? extends DetectionOverride>> overridesByRoot
    ) {
        var result = new HashMap<String, List<SchemaOverride>>();
        for (var entry : overridesByRoot.entrySet()) {
            var schemaOverrides = entry.getValue().stream()
                .filter(o -> o instanceof SchemaOverride)
                .map(o -> (SchemaOverride) o)
                .toList();
            if (!schemaOverrides.isEmpty()) {
                result.put(entry.getKey(), schemaOverrides);
            }
        }
        return result;
    }

    private Map<String, DataType> detectColumnDataTypesForRoot(ImportDataSource dataSource) {
        var columnDataTypes = new HashMap<String, DataType>();
        var columnNames = dataSource.getColumnNames();
        var coercionStrategy = InMemoryCoercion.Skip.INSTANCE;

        var columnSamples = new HashMap<String, List<Object>>();
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
            var dataType = typeDetector.detectType(samples, coercionStrategy);
            columnDataTypes.put(columnName, dataType);
        }

        return columnDataTypes;
    }

    private void applyTypeOverridesRecursive(Map<String, DataType> columnDataTypes, List<SchemaOverride> overrides) {
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
        var columnPrefix = convertAttributeNameToColumn(attributeName, namingStyle);
        return parentPrefix.isEmpty() ?
            columnPrefix :
            parentPrefix + namingStyle.getSeparator() + columnPrefix;
    }

    private String convertAttributeNameToColumn(String attributeName, NamingStyle namingStyle) {
        var parts = attributeName.split("(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");
        return namingStyle.join(parts);
    }

    private Optional<String> findColumnForAttribute(
        String attributeName,
        List<String> searchColumns,
        String parentPrefix
    ) {
        var namingStyle = namingStyleDetector.detect(searchColumns);
        var columnPrefix = convertAttributeNameToColumn(attributeName, namingStyle);

        var expectedColumn = parentPrefix.isEmpty() ?
            columnPrefix :
            parentPrefix + namingStyle.getSeparator() + columnPrefix;

        if (searchColumns.contains(expectedColumn)) {
            return Optional.of(expectedColumn);
        }

        return searchColumns.stream()
            .filter(col -> col.equalsIgnoreCase(expectedColumn))
            .findFirst();
    }

    private void enrichAttributesWithTypes(
        Map<String, DetectedAttribute> attributes,
        Map<String, DataType> columnDataTypes
    ) {
        var enrichedAttributes = new HashMap<String, DetectedAttribute>();
        for (var entry : attributes.entrySet()) {
            var name = entry.getKey();
            var attr = entry.getValue();
            enrichedAttributes.put(name, enrichAttributeWithType(attr, columnDataTypes));
        }
        attributes.clear();
        attributes.putAll(enrichedAttributes);
    }

    private DetectedAttribute enrichAttributeWithType(
        DetectedAttribute attr,
        Map<String, DataType> columnDataTypes
    ) {
        return switch (attr) {
            case DetectedAttribute.Basic basic -> new DetectedAttribute.Basic(
                basic.name(),
                basic.source(),
                columnDataTypes.get(basic.source().sourceColumn())
            );
            case DetectedAttribute.Collection coll -> new DetectedAttribute.Collection(
                coll.name(),
                coll.source(),
                coll.separator(),
                columnDataTypes.get(coll.source().sourceColumn())
            );
            case DetectedAttribute.SingularReference ref -> new DetectedAttribute.SingularReference(
                ref.name(),
                ref.source(),
                ref.targetRootName(),
                columnDataTypes.get(ref.source().sourceColumn())
            );
            case DetectedAttribute.PluralReference ref -> new DetectedAttribute.PluralReference(
                ref.name(),
                ref.source(),
                ref.targetRootName(),
                columnDataTypes.get(ref.source().sourceColumn())
            );
            case DetectedAttribute.Composite comp -> new DetectedAttribute.Composite(
                comp.name(),
                enrichSubAttributesWithTypes(comp.subAttributes(), columnDataTypes)
            );
            case DetectedAttribute.OneToOneRoot oneToOne -> new DetectedAttribute.OneToOneRoot(
                oneToOne.name(),
                oneToOne.targetRootName(),
                enrichSubAttributesWithTypes(oneToOne.subAttributes(), columnDataTypes)
            );
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
        String defaultListSeparator,
        String dataSourceName,
        String currentRootName
    ) {
        var columnNames = new ArrayList<>(dataSource.getColumnNames());

        overrides.stream()
            .filter(o -> o instanceof SchemaOverride.IdAttributeOverride)
            .map(o -> (SchemaOverride.IdAttributeOverride) o)
            .findFirst()
            .ifPresent(idAttributeOverride ->
                columnNames.remove(idAttributeOverride.columnName())
            );

        var namingStyle = namingStyleDetector.detect(columnNames);

        var detector = new AttributeTypeDetector(
            rootNames,
            namingStyle,
            overrides,
            defaultListSeparator,
            dataSourceName,
            currentRootName
        );

        return detector.detectAttributes(columnNames);
    }

    private Map<String, DetectedRoot> collectAndCreateOneToOneRoots(
        Map<String, DetectedRoot> detectedRoots,
        List<SchemaOverride> overrides
    ) {
        var oneToOneRoots = new HashMap<String, DetectedRoot>();
        for (var root : detectedRoots.values()) {
            collectOneToOneRootsRecursive(root.attributes().values(), root.sourceDataSource(), oneToOneRoots, overrides);
        }
        return oneToOneRoots;
    }

    private void collectOneToOneRootsRecursive(
        Collection<DetectedAttribute> attributes,
        String sourceDataSource,
        Map<String, DetectedRoot> oneToOneRoots,
        List<SchemaOverride> overrides
    ) {
        for (var attr : attributes) {
            switch (attr) {
                case DetectedAttribute.Composite composite ->
                    collectOneToOneRootsRecursive(composite.subAttributes().values(), sourceDataSource, oneToOneRoots, overrides);
                case DetectedAttribute.OneToOneRoot oneToOne -> {
                    var rootName = oneToOne.targetRootName();
                    if (!oneToOneRoots.containsKey(rootName)) {
                        var idColumnOverride = findOneToOneIdColumnOverride(rootName, overrides);
                        var idColumn = detectIdColumnForOneToOneRoot(oneToOne.subAttributes(), idColumnOverride);

                        oneToOneRoots.put(rootName, new DetectedRoot(
                            rootName,
                            sourceDataSource,
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
                    var nested = findOneToOneIdColumnOverride(targetRootName, oneToOne.nestedOverrides());
                    if (nested != null) {
                        return nested;
                    }
                }
                case SchemaOverride.CompositeAttributeOverride composite -> {
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
        if (overriddenIdColumn != null) {
            var overriddenAttr = subAttributes.values().stream()
                .filter(attr -> attr instanceof DetectedAttribute.Basic)
                .map(attr -> (DetectedAttribute.Basic) attr)
                .filter(basic -> basic.source().sourceColumn().equalsIgnoreCase(overriddenIdColumn))
                .findFirst()
                .orElse(null);

            if (overriddenAttr != null) {
                var dataType = Objects.requireNonNullElseGet(
                    overriddenAttr.dataType(),
                    () -> new DataType.NumericType(19, 0)
                );
                return new DetectedIdColumn(overriddenAttr.name(), overriddenAttr.source().sourceColumn(), dataType);
            }
        }

        var idAttr = subAttributes.values().stream()
            .filter(attr -> attr instanceof DetectedAttribute.Basic)
            .map(attr -> (DetectedAttribute.Basic) attr)
            .filter(basic -> basic.source().sourceColumn().toLowerCase().endsWith("_id"))
            .findFirst()
            .orElse(null);

        if (idAttr != null) {
            var dataType = Objects.requireNonNullElseGet(
                idAttr.dataType(),
                () -> new DataType.NumericType(19, 0)
            );
            return new DetectedIdColumn(idAttr.name(), idAttr.source().sourceColumn(), dataType);
        }

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
            .findFirst();

        if (idOverride.isPresent()) {
            var override = idOverride.get();
            var dataType = override.dataType();
            if (dataType == null) {
                dataType = columnDataTypes.getOrDefault(
                    override.columnName(),
                    new DataType.NumericType(19, 0)
                );
            }
            var naming = namingStyleDetector.detect(dataSource.getColumnNames());
            return new DetectedIdColumn(
                Objects.requireNonNullElse(override.attributeName(), naming.forceAdjust(override.columnName())),
                override.columnName(),
                dataType
            );
        } else {
            var idColumn = dataSource.getColumnNames().stream()
                .filter(c -> c.equalsIgnoreCase("id"))
                .findFirst()
                .orElse("id");
            var dataType = columnDataTypes.getOrDefault(idColumn, new DataType.NumericType(19, 0));
            return new DetectedIdColumn("id", idColumn, dataType);
        }
    }
}
