package com.rorm.dataimport.pipeline;

import com.rorm.dataimport.attribute.DetectedAttribute;
import com.rorm.metamodel.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Converts detected schema to metamodel (Roots and Attributes).
 */
public class MetamodelConverter {

    private HashMap<String, Root> createMutableRootMap(DetectedSchema detectedSchema) {
        var rootMap = new HashMap<String, Root>();

        for (var detectedRoot : detectedSchema.roots().values()) {
            var rootName = detectedRoot.name();
            var idColumn = detectedRoot.idColumn();
            var idDescriptor = new IdDescriptor(new BasicAttribute(
                idColumn.attributeName(),
                new AttributeLocation(rootName, idColumn.columnName()),
                idColumn.dataType()
            ));
            rootMap.put(rootName, new Root(rootName, new ArrayList<>(), idDescriptor));
        }
        return rootMap;
    }

    /**
     * Converts detected schema to ModelSpace.
     */
    public ModelSpace convertToModelSpace(DetectedSchema detectedSchema) {
        var rootMap = createMutableRootMap(detectedSchema);

        for (var detectedRoot : detectedSchema.roots().values()) {
            var rootName = detectedRoot.name();
            var root = rootMap.get(rootName);

            // Add the ID attribute from the IdDescriptor to the attributes list
            // The ID should be both in the descriptor AND in the attributes
            root.attributes().add(root.idDescriptor().idAttribute());

            // Add all other attributes
            var attributes = convertToAttributes(
                detectedRoot.attributes(),
                rootName,
                rootMap
            );
            root.attributes().addAll(attributes);
        }

        return new ModelSpace(
            rootMap.values().stream()
                .map(root -> new Root(root.primaryTableName(), List.copyOf(root.attributes()), root.idDescriptor()))
                .collect(Collectors.toUnmodifiableSet())
        );
    }

    private List<Attribute> convertToAttributes(
        Map<String, DetectedAttribute> detectedAttributes,
        String tableName,
        Map<String, Root> rootMap
    ) {
        return detectedAttributes.values().stream()
            .map(detected -> convertAttribute(detected, tableName, rootMap))
            .toList();
    }

    private Attribute convertAttribute(
        DetectedAttribute detected,
        String tableName,
        Map<String, Root> rootMap
    ) {
        return switch (detected) {
            case DetectedAttribute.Basic basic -> new BasicAttribute(
                basic.name(),
                new AttributeLocation(tableName, basic.source().sourceColumn()),
                basic.dataType() != null ? basic.dataType() : new DataType.StringType()
            );
            case DetectedAttribute.Collection collection -> new CollectionAttribute(
                collection.name(),
                tableName,
                new CollectionAttribute.BasicElement(
                    new AttributeLocation(tableName, collection.source().sourceColumn()),
                    collection.elementType() != null ? collection.elementType() : new DataType.StringType()
                )
            );
            case DetectedAttribute.SingularReference ref -> {
                var targetRoot = rootMap.get(ref.targetRootName());
                if (targetRoot == null) {
                    throw new IllegalStateException("Target root not found: " + ref.targetRootName());
                }
                yield new SingularReferenceAttribute(
                    ref.name(),
                    targetRoot,
                    new ReferenceAttribute.SameTableColumn(ref.source().sourceColumn())
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
                    new ReferenceAttribute.SameTableColumn(ref.source().sourceColumn())
                );
            }
            case DetectedAttribute.Composite composite -> {
                var subAttributes = composite.subAttributes().values().stream()
                    .map(sub -> convertAttribute(sub, tableName, rootMap))
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
