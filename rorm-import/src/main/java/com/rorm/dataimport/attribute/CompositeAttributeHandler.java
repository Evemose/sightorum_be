package com.rorm.dataimport.attribute;

import com.rorm.dataimport.naming.NamingStyle;
import com.rorm.dataimport.override.SchemaOverride;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

class CompositeAttributeHandler implements AttributeDetectionHandler {

    private final Set<String> availableRootNames;
    private final NamingStyle namingStyle;
    private final Map<String, SchemaOverride> overrideMap;

    CompositeAttributeHandler(
        Set<String> availableRootNames,
        NamingStyle namingStyle,
        List<SchemaOverride> overrides
    ) {
        this.availableRootNames = availableRootNames;
        this.namingStyle = namingStyle;
        this.overrideMap = overrides.stream()
            .collect(Collectors.toMap(SchemaOverride::attributeName, Function.identity()));
    }

    @Override
    public Set<String> handle(List<String> columnNames, Map<String, DetectedAttribute> result) {
        var claimedColumns = new HashSet<String>();
        var prefixGroups = groupColumnsByPrefix(columnNames);

        prefixGroups.entrySet().stream()
            .filter(entry -> entry.getValue().size() > 1)
            .map(entry -> buildCompositeAttribute(entry, claimedColumns))
            .flatMap(Optional::stream)
            .forEach(entry -> result.put(entry.getKey(), entry.getValue()));

        return claimedColumns;
    }

    private Map<String, List<String>> groupColumnsByPrefix(List<String> columnNames) {
        return columnNames.stream()
            .map(this::extractPrefixAndColumn)
            .flatMap(Optional::stream)
            .collect(Collectors.groupingBy(
                Map.Entry::getKey,
                LinkedHashMap::new,
                Collectors.mapping(Map.Entry::getValue, Collectors.toList())
            ));
    }

    private Optional<Map.Entry<String, DetectedAttribute>> buildCompositeAttribute(
        Map.Entry<String, List<String>> prefixGroup,
        Set<String> claimedColumns
    ) {
        var prefix = prefixGroup.getKey();
        var columns = prefixGroup.getValue();
        var prefixParts = namingStyle.split(prefix);
        var compositeName = NamingStyle.toCamelCase(prefixParts);

        // Skip if an override exists for this attribute name
        if (overrideMap.containsKey(compositeName)) {
            return Optional.empty();
        }

        if (isOneToOneRoot(columns, prefix)) {
            var subAttrs = createSubAttributes(columns, claimedColumns);
            return Optional.of(Map.entry(
                compositeName,
                new DetectedAttribute.OneToOneRoot(compositeName, prefix, subAttrs)
            ));
        }

        // If prefix is an available root and there's an id column, exclude it from the composite
        // (it will be handled by ReferenceAttributeHandler)
        var columnsToProcess = columns;
        if (availableRootNames.contains(prefix)) {
            var idColumn = namingStyle.join(prefix, "id");
            columnsToProcess = columns.stream()
                .filter(col -> !col.equals(idColumn))
                .toList();
        }

        // Only create composite if there are still columns to process
        if (columnsToProcess.isEmpty()) {
            return Optional.empty();
        }

        var subAttrs = createSubAttributes(columnsToProcess, claimedColumns);
        return Optional.of(Map.entry(
            compositeName,
            new DetectedAttribute.Composite(compositeName, subAttrs)
        ));
    }

    private Optional<Map.Entry<String, String>> extractPrefixAndColumn(String column) {
        var parts = namingStyle.split(column);
        if (parts.length > 1) {
            var prefix = String.join(namingStyle.getSeparator(), Arrays.copyOf(parts, parts.length - 1));
            return Optional.of(Map.entry(prefix, column));
        }
        return Optional.empty();
    }

    private boolean isOneToOneRoot(List<String> columns, String prefix) {
        var hasIdColumn = columns.stream()
            .anyMatch(col -> col.equals(namingStyle.join(prefix, "id")));
        return hasIdColumn && !availableRootNames.contains(prefix);
    }

    private Map<String, DetectedAttribute> createSubAttributes(
        List<String> columns,
        Set<String> claimedColumns
    ) {
        return columns.stream()
            .peek(claimedColumns::add)
            .map(column -> {
                var parts = namingStyle.split(column);
                var suffix = parts[parts.length - 1];
                var subAttrName = NamingStyle.toCamelCase(new String[]{suffix});
                return Map.entry(
                    subAttrName,
                    (DetectedAttribute) new DetectedAttribute.Basic(subAttrName, column)
                );
            })
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                Map.Entry::getValue,
                (a, _) -> a,
                LinkedHashMap::new
            ));
    }
}
