package com.rorm.dataimport.attribute;

import com.rorm.dataimport.naming.NamingStyle;
import com.rorm.dataimport.override.SchemaOverride;

import java.util.*;

class ExplicitOverrideHandler implements AttributeDetectionHandler {

    private final NamingStyle namingStyle;
    private final List<SchemaOverride> overrides;
    private final String defaultListSeparator;
    private final CompositeAttributeBuilder compositeBuilder;

    ExplicitOverrideHandler(
        NamingStyle namingStyle,
        List<SchemaOverride> overrides,
        String defaultListSeparator
    ) {
        this.namingStyle = namingStyle;
        this.overrides = overrides;
        this.defaultListSeparator = defaultListSeparator;
        this.compositeBuilder = new CompositeAttributeBuilder(namingStyle, defaultListSeparator);
    }

    @Override
    public Set<String> handle(List<String> columnNames, Map<String, DetectedAttribute> result) {
        var claimedColumns = new HashSet<String>();
        overrides.stream()
            .map(override -> processOverride(override, columnNames, claimedColumns))
            .flatMap(Optional::stream)
            .forEach(detected -> result.put(detected.name(), detected));
        return claimedColumns;
    }

    private Optional<DetectedAttribute> processOverride(
        SchemaOverride override,
        List<String> columnNames,
        Set<String> claimedColumns
    ) {
        return switch (override) {
            case SchemaOverride.BasicAttributeOverride basic ->
                processBasicOverride(basic, columnNames, claimedColumns);
            case SchemaOverride.CompositeAttributeOverride composite ->
                processCompositeOverride(composite, claimedColumns);
            case SchemaOverride.SingularReferenceOverride ref ->
                processSingularReferenceOverride(ref, columnNames, claimedColumns);
            case SchemaOverride.PluralReferenceOverride ref ->
                processPluralReferenceOverride(ref, columnNames, claimedColumns);
            case SchemaOverride.CollectionAttributeOverride coll ->
                processCollectionOverride(coll, columnNames, claimedColumns);
            case SchemaOverride.OneToOneRootOverride oneToOne -> processOneToOneRootOverride(oneToOne, claimedColumns);
        };
    }

    private Optional<DetectedAttribute> processBasicOverride(
        SchemaOverride.BasicAttributeOverride basic,
        List<String> columnNames,
        Set<String> claimedColumns
    ) {
        return findColumnForAttribute(basic.attributeName(), columnNames)
            .map(column -> {
                claimedColumns.add(column);
                return new DetectedAttribute.Basic(basic.attributeName(), column, basic.descriptor());
            });
    }

    private Optional<DetectedAttribute> processCompositeOverride(
        SchemaOverride.CompositeAttributeOverride composite,
        Set<String> claimedColumns
    ) {
        var subAttrs = compositeBuilder.buildSubAttributes(
            composite.subAttributeColumns(),
            composite.nestedOverrides(),
            claimedColumns
        );
        return Optional.of(new DetectedAttribute.Composite(composite.attributeName(), subAttrs));
    }

    private Optional<DetectedAttribute> processSingularReferenceOverride(
        SchemaOverride.SingularReferenceOverride ref,
        List<String> columnNames,
        Set<String> claimedColumns
    ) {
        return findColumnForAttribute(ref.attributeName(), columnNames)
            .map(column -> {
                claimedColumns.add(column);
                return new DetectedAttribute.SingularReference(ref.attributeName(), column, ref.targetRootName());
            });
    }

    private Optional<DetectedAttribute> processPluralReferenceOverride(
        SchemaOverride.PluralReferenceOverride ref,
        List<String> columnNames,
        Set<String> claimedColumns
    ) {
        return findColumnForAttribute(ref.attributeName(), columnNames)
            .map(column -> {
                claimedColumns.add(column);
                return new DetectedAttribute.PluralReference(ref.attributeName(), column, ref.targetRootName());
            });
    }

    private Optional<DetectedAttribute> processCollectionOverride(
        SchemaOverride.CollectionAttributeOverride coll,
        List<String> columnNames,
        Set<String> claimedColumns
    ) {
        return findColumnForAttribute(coll.attributeName(), columnNames)
            .map(column -> {
                claimedColumns.add(column);
                return new DetectedAttribute.Collection(
                    coll.attributeName(),
                    column,
                    Objects.requireNonNullElse(coll.separator(), defaultListSeparator)
                );
            });
    }

    private Optional<DetectedAttribute> processOneToOneRootOverride(
        SchemaOverride.OneToOneRootOverride oneToOne,
        Set<String> claimedColumns
    ) {
        var subAttrs = compositeBuilder.buildSubAttributes(
            oneToOne.subAttributeColumns(),
            oneToOne.nestedOverrides(),
            claimedColumns
        );
        return Optional.of(new DetectedAttribute.OneToOneRoot(
            oneToOne.attributeName(),
            oneToOne.targetRootName(),
            subAttrs
        ));
    }

    private Optional<String> findColumnForAttribute(String attributeName, List<String> columnNames) {
        return columnNames.stream()
            .filter(column -> {
                var parts = namingStyle.split(column);
                var attrName = NamingStyle.toCamelCase(parts);
                return attrName.equals(attributeName);
            })
            .findFirst();
    }
}
