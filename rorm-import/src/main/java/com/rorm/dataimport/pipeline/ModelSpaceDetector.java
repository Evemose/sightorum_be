package com.rorm.dataimport.pipeline;

import com.rorm.dataimport.attribute.AttributeTypeDetector;
import com.rorm.dataimport.attribute.DetectedAttribute;
import com.rorm.dataimport.naming.NamingStyleDetector;
import com.rorm.dataimport.override.SchemaOverride;
import com.rorm.dataimport.source.ImportDataSource;
import com.rorm.metamodel.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class ModelSpaceDetector {

    private final NamingStyleDetector namingStyleDetector;

    public ModelSpace detectModelSpace(
        List<ImportDataSource> dataSources,
        List<SchemaOverride> overrides,
        String defaultListSeparator
    ) {
        var rootNames = dataSources.stream()
            .map(ImportDataSource::getRootName)
            .collect(Collectors.toSet());

        // First pass: detect attributes for each data source
        var detectedRoots = detectAttributesFromDataSources(dataSources, rootNames, overrides, defaultListSeparator);

        // Second pass: collect OneToOneRoot definitions
        var oneToOneRoots = collectOneToOneRoots(detectedRoots);

        // Third pass: combine all roots with detected attributes
        var allDetectedRootDescriptions = new HashMap<String, Map<String, DetectedAttribute>>();
        allDetectedRootDescriptions.putAll(detectedRoots);
        allDetectedRootDescriptions.putAll(oneToOneRoots);

        // Fourth pass: create mutable Root objects with mutable attribute lists
        var rootMap = new HashMap<String, Root>();
        for (var rootName : allDetectedRootDescriptions.keySet()) {
            rootMap.put(rootName, new Root(rootName, new ArrayList<>()));
        }

        // Fifth pass: convert attributes and populate mutable lists
        for (var entry : allDetectedRootDescriptions.entrySet()) {
            var rootName = entry.getKey();
            var detectedAttributes = entry.getValue();
            var attributes = convertToAttributes(detectedAttributes, rootName, rootMap);
            rootMap.get(rootName).attributes().addAll(attributes);
        }

        // Sixth pass: freeze roots by replacing mutable lists with immutable ones
        return new ModelSpace(
            rootMap.values().stream()
                .map(root -> new Root(root.primaryTableName(), List.copyOf(root.attributes())))
                .collect(Collectors.toUnmodifiableSet())
        );
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
        java.util.Collection<DetectedAttribute> attributes,
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
                new AttributeLocation(tableName, basic.columnName())
            );
            case DetectedAttribute.Collection collection -> new CollectionAttribute(
                collection.name(),
                tableName,
                new CollectionAttribute.BasicElement(
                    new AttributeLocation(tableName, collection.columnName())
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
