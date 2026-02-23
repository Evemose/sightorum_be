package com.rorm.dataimport.pipeline;

import com.rorm.dataimport.attribute.DetectedAttribute;
import com.rorm.metamodel.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Converts detected schema to metamodel (Roots and Attributes).
 */
public class MetamodelConverter {

    /**
     * Converts detected schema to ModelSpace.
     */
    public ModelSpace convertToModelSpace(DetectedSchema detectedSchema) {
        var specs = detectedSchema.roots().values().stream()
            .map(RootBuildSpec::from)
            .toList();

        // Mutable root map — references need to see attributes added later
        var rootMap = specs.stream()
            .collect(Collectors.toMap(
                RootBuildSpec::name,
                spec -> new Root(spec.name(), new ArrayList<>(), spec.idDescriptor())
            ));

        for (var spec : specs) {
            var root = rootMap.get(spec.name());
            root.attributes().add(spec.idDescriptor().idAttribute());
            root.attributes().addAll(convertToAttributes(spec.attrs(), spec.name(), rootMap));
        }

        return new ModelSpace(
            rootMap.values().stream()
                .map(root -> new Root(root.primaryTableName(), List.copyOf(root.attributes()), root.idDescriptor()))
                .collect(Collectors.toUnmodifiableSet())
        );
    }

    // 6A: RootBuildSpec eliminates mutable root.attributes().add() pattern
    private record RootBuildSpec(String name, IdDescriptor idDescriptor, Map<String, DetectedAttribute> attrs) {
        static RootBuildSpec from(SchemaDetector.DetectedRoot dr) {
            var idColumn = dr.idColumn();
            var idDescriptor = new IdDescriptor(new BasicAttribute(
                idColumn.attributeName(),
                new AttributeLocation(dr.name(), idColumn.columnName()),
                idColumn.dataType()
            ));
            return new RootBuildSpec(dr.name(), idDescriptor, dr.attributes());
        }
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
