package com.rorm.dataimport.pipeline;

import com.rorm.dataimport.attribute.DetectedAttribute;
import com.rorm.metamodel.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Converts detected schema to metamodel (Roots and Attributes).
 */
@Component
class MetamodelConverter {

    /**
     * Converts detected schema to ModelSpace.
     */
    public ModelSpace convertToModelSpace(SchemaDetector.DetectedSchema detectedSchema) {
        var detectedAttributes = detectedSchema.detectedAttributes();
        var columnDataTypes = detectedSchema.columnDataTypes();
        var typeOverrides = detectedSchema.typeOverrides();

        // Create mutable Root objects with mutable attribute lists
        var rootMap = new HashMap<String, Root>();
        for (var rootName : detectedAttributes.keySet()) {
            rootMap.put(rootName, new Root(rootName, new ArrayList<>()));
        }

        // Convert attributes and populate mutable lists
        for (var entry : detectedAttributes.entrySet()) {
            var rootName = entry.getKey();
            var attrs = entry.getValue();
            var types = columnDataTypes.getOrDefault(rootName, Map.of());
            var attributes = convertToAttributes(attrs, rootName, rootMap, types, typeOverrides);
            rootMap.get(rootName).attributes().addAll(attributes);
        }

        // Freeze roots by replacing mutable lists with immutable ones
        return new ModelSpace(
            rootMap.values().stream()
                .map(root -> new Root(root.primaryTableName(), List.copyOf(root.attributes())))
                .collect(Collectors.toUnmodifiableSet())
        );
    }

    private List<Attribute> convertToAttributes(
        Map<String, DetectedAttribute> detectedAttributes,
        String tableName,
        Map<String, Root> rootMap,
        Map<String, DataType> columnDataTypes,
        Map<String, DataType> typeOverrides
    ) {
        return detectedAttributes.values().stream()
            .map(detected -> convertAttribute(detected, tableName, rootMap, columnDataTypes, typeOverrides))
            .toList();
    }

    private Attribute convertAttribute(
        DetectedAttribute detected,
        String tableName,
        Map<String, Root> rootMap,
        Map<String, DataType> columnDataTypes,
        Map<String, DataType> typeOverrides
    ) {
        return switch (detected) {
            case DetectedAttribute.Basic basic -> {
                // Priority: 1) explicit override, 2) detected type from column data, 3) fallback to string
                var dataType = typeOverrides.getOrDefault(
                    basic.name(),
                    columnDataTypes.getOrDefault(basic.columnName(), new DataType.StringType())
                );
                yield new BasicAttribute(
                    basic.name(),
                    new AttributeLocation(tableName, basic.columnName()),
                    dataType
                );
            }
            case DetectedAttribute.Collection collection -> {
                // Priority: 1) explicit override, 2) detected type from column data, 3) fallback to string
                var elementType = typeOverrides.getOrDefault(
                    collection.name(),
                    columnDataTypes.getOrDefault(collection.columnName(), new DataType.StringType())
                );
                yield new CollectionAttribute(
                    collection.name(),
                    tableName,
                    new CollectionAttribute.BasicElement(
                        new AttributeLocation(tableName, collection.columnName()),
                        elementType
                    )
                );
            }
            case DetectedAttribute.SingularReference ref -> {
                var targetRoot = rootMap.get(ref.targetRootName());
                if (targetRoot == null) {
                    throw new IllegalStateException("Target root not found: " + ref.targetRootName());
                }
                yield new SingularReferenceAttribute(
                    ref.name(),
                    targetRoot,
                    new ReferenceAttribute.InverseRootTableColumn(ref.columnName())
                );
            }
            case DetectedAttribute.PluralReference ref -> {
                var targetRoot = rootMap.get(ref.targetRootName());
                if (targetRoot == null) {
                    throw new IllegalStateException("Target root not found: " + ref.targetRootName());
                }
                yield new PluralReferenceAttribute(
                    ref.name(),
                    targetRoot,
                    new ReferenceAttribute.InverseRootTableColumn(ref.columnName())
                );
            }
            case DetectedAttribute.Composite composite -> {
                var subAttributes = composite.subAttributes().values().stream()
                    .map(sub -> convertAttribute(sub, tableName, rootMap, columnDataTypes, typeOverrides))
                    .collect(Collectors.toSet());
                yield new CompositeAttribute(composite.name(), subAttributes);
            }
            case DetectedAttribute.OneToOneRoot oneToOne -> {
                var targetRoot = rootMap.get(oneToOne.targetRootName());
                if (targetRoot == null) {
                    throw new IllegalStateException("Target root not found: " + oneToOne.targetRootName());
                }
                yield new SingularReferenceAttribute(
                    oneToOne.name(),
                    targetRoot,
                    new ReferenceAttribute.InverseRootTableColumn(oneToOne.name() + "_id")
                );
            }
        };
    }
}
