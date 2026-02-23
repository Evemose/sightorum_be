package com.rorm.dataimport.attribute;

import com.rorm.dataimport.naming.NamingStyle;
import com.rorm.dataimport.override.SchemaOverride;
import com.rorm.dataimport.pipeline.SourceMapping;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

class CompositeAttributeHandler implements AttributeDetectionHandler {

    private final Set<String> availableRootNames;
    private final Set<String> rootSingularNames;
    private final NamingStyle namingStyle;
    private final Map<String, SchemaOverride> overrideMap;
    private final String dataSourceName;

    CompositeAttributeHandler(
        Set<String> availableRootNames,
        NamingStyle namingStyle,
        List<SchemaOverride> overrides,
        String dataSourceName
    ) {
        this.availableRootNames = availableRootNames;
        this.rootSingularNames = availableRootNames.stream()
            .map(NameUtils::singularize)
            .collect(Collectors.toSet());
        this.namingStyle = namingStyle;
        this.overrideMap = overrides.stream()
            .collect(
                Collectors.toMap(
                    SchemaOverride::attributeName,
                    Function.identity(),
                    (a, _) -> {
                        throw new IllegalArgumentException(
                            "Duplicate override for attribute: " + a.attributeName()
                        );
                    }
                )
            );
        this.dataSourceName = dataSourceName;
    }

    @Override
    public Set<String> handle(List<String> columnNames, Map<String, DetectedAttribute> result) {
        var claimedColumns = new HashSet<String>();
        var prefixGroups = groupColumnsByPrefix(columnNames);

        prefixGroups.entrySet().stream()
            .filter(entry -> entry.getValue().size() > 1)
            .map(entry -> buildCompositeAttribute(entry.getKey(), entry.getValue(), claimedColumns))
            .flatMap(Optional::stream)
            .forEach(entry -> result.put(entry.getKey(), entry.getValue()));

        return claimedColumns;
    }

    private Map<String, List<String>> groupColumnsByPrefix(List<String> columnNames) {
        return columnNames.stream()
            .map(this::extractPrefixAndColumn)
            .flatMap(Optional::stream)
            .filter(entry -> !rootSingularNames.contains(entry.getKey())) // Skip root singular prefixes
            .collect(Collectors.groupingBy(
                Map.Entry::getKey,
                LinkedHashMap::new,
                Collectors.mapping(Map.Entry::getValue, Collectors.toList())
            ));
    }

    // 5C: Flattened with early returns and extracted helpers
    private Optional<Map.Entry<String, DetectedAttribute>> buildCompositeAttribute(
        String prefix,
        List<String> columns,
        Set<String> claimedColumns
    ) {
        var compositeName = toAttributeName(prefix);

        if (overrideMap.containsKey(compositeName)) {
            return Optional.empty();
        }

        if (isOneToOneRoot(columns, prefix)) {
            var subAttrs = createSubAttributes(columns);
            claimedColumns.addAll(columns);
            return Optional.of(Map.entry(
                compositeName,
                new DetectedAttribute.OneToOneRoot(compositeName, prefix, subAttrs)
            ));
        }

        var columnsToProcess = excludeRootIdColumn(columns, prefix);
        if (columnsToProcess.isEmpty()) {
            return Optional.empty();
        }

        var subAttrs = createSubAttributes(columnsToProcess);
        claimedColumns.addAll(columnsToProcess);
        return Optional.of(Map.entry(
            compositeName,
            new DetectedAttribute.Composite(compositeName, subAttrs)
        ));
    }

    private String toAttributeName(String prefix) {
        return NamingStyle.toCamelCase(namingStyle.split(prefix));
    }

    // 5A: Removed peek side-effect; 5B: Extracted columnToSubAttribute
    private Map<String, DetectedAttribute> createSubAttributes(List<String> columns) {
        return columns.stream()
            .map(this::columnToSubAttribute)
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                Map.Entry::getValue,
                (a, _) -> a,
                LinkedHashMap::new
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

    private List<String> excludeRootIdColumn(List<String> columns, String prefix) {
        if (!availableRootNames.contains(prefix)) {
            return columns;
        }
        var idColumn = namingStyle.join(prefix, "id");
        return columns.stream()
            .filter(col -> !col.equals(idColumn))
            .toList();
    }

    private Map.Entry<String, DetectedAttribute> columnToSubAttribute(String column) {
        var parts = namingStyle.split(column);
        var suffix = parts[parts.length - 1];
        var subAttrName = NamingStyle.toCamelCase(new String[]{suffix});
        var source = new SourceMapping(dataSourceName, column);
        return Map.entry(subAttrName, new DetectedAttribute.Basic(subAttrName, source, null));
    }
}
