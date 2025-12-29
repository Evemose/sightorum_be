package com.rorm.dataimport.attribute;

import com.rorm.dataimport.naming.NamingStyle;
import com.rorm.dataimport.override.SchemaOverride;
import org.jspecify.annotations.Nullable;

import java.util.*;

class CompositeAttributeBuilder {

    private final NamingStyle namingStyle;
    private final String defaultListSeparator;

    CompositeAttributeBuilder(NamingStyle namingStyle, String defaultListSeparator) {
        this.namingStyle = namingStyle;
        this.defaultListSeparator = defaultListSeparator;
    }

    Map<String, DetectedAttribute> buildSubAttributes(
        List<String> columns,
        List<SchemaOverride> nestedOverrides,
        Set<String> claimedColumns
    ) {
        var subAttrs = new LinkedHashMap<String, DetectedAttribute>();
        var prefix = extractCommonPrefix(columns);
        var prefixPartCount = prefix.map(s -> namingStyle.split(s).length).orElse(0);

        // Process nested overrides first
        nestedOverrides.stream()
            .map(override -> processNestedOverride(override, columns, claimedColumns, prefix.orElse(null)))
            .flatMap(Optional::stream)
            .forEach(attr -> subAttrs.put(attr.name(), attr));

        // Process remaining columns
        columns.stream()
            .filter(col -> !claimedColumns.contains(col))
            .forEach(col -> {
                claimedColumns.add(col);
                var name = extractAttributeName(col, prefixPartCount);
                subAttrs.putIfAbsent(name, new DetectedAttribute.Basic(name, col, "string"));
            });

        return subAttrs;
    }

    private Optional<String> extractCommonPrefix(List<String> columns) {
        if (columns.isEmpty()) {
            return Optional.empty();
        }

        var allParts = columns.stream().map(namingStyle::split).toList();
        if (allParts.stream().anyMatch(parts -> parts.length < 2)) {
            return Optional.empty();
        }

        var minLength = allParts.stream().mapToInt(parts -> parts.length).min().orElse(0);
        var prefixParts = new ArrayList<String>();

        for (var i = 0; i < minLength - 1; i++) {
            var part = allParts.getFirst()[i];
            var index = i;
            if (!allParts.stream().allMatch(parts -> parts[index].equals(part))) {
                break;
            }
            prefixParts.add(part);
        }

        return prefixParts.isEmpty() ?
            Optional.empty() :
            Optional.of(String.join(namingStyle.getSeparator(), prefixParts));
    }

    private String extractAttributeName(String column, int prefixPartCount) {
        var parts = namingStyle.split(column);
        if (prefixPartCount == 0 || parts.length <= prefixPartCount) {
            return NamingStyle.toCamelCase(parts);
        }
        return NamingStyle.toCamelCase(Arrays.copyOfRange(parts, prefixPartCount, parts.length));
    }

    private Optional<DetectedAttribute> processNestedOverride(
        SchemaOverride override,
        List<String> columns,
        Set<String> claimedColumns,
        @Nullable String prefix
    ) {
        return switch (override) {
            case SchemaOverride.BasicAttributeOverride o ->
                findAndClaimColumn(o.attributeName(), columns, claimedColumns, prefix)
                    .map(col -> new DetectedAttribute.Basic(o.attributeName(), col, o.descriptor()));
            case SchemaOverride.SingularReferenceOverride o ->
                findAndClaimColumn(o.attributeName(), columns, claimedColumns, prefix)
                    .map(col -> new DetectedAttribute.SingularReference(o.attributeName(), col, o.targetRootName()));
            case SchemaOverride.PluralReferenceOverride o ->
                findAndClaimColumn(o.attributeName(), columns, claimedColumns, prefix)
                    .map(col -> new DetectedAttribute.PluralReference(o.attributeName(), col, o.targetRootName()));
            case SchemaOverride.CollectionAttributeOverride o ->
                findAndClaimColumn(o.attributeName(), columns, claimedColumns, prefix)
                    .map(col -> new DetectedAttribute.Collection(
                        o.attributeName(), col, Objects.requireNonNullElse(o.separator(), defaultListSeparator)));
            case SchemaOverride.CompositeAttributeOverride o -> Optional.of(new DetectedAttribute.Composite(
                o.attributeName(),
                buildSubAttributes(o.subAttributeColumns(), o.nestedOverrides(), claimedColumns)));
            case SchemaOverride.OneToOneRootOverride o -> Optional.of(new DetectedAttribute.OneToOneRoot(
                o.attributeName(),
                o.targetRootName(),
                buildSubAttributes(o.subAttributeColumns(), o.nestedOverrides(), claimedColumns)));
        };
    }

    private Optional<String> findAndClaimColumn(
        String attributeName,
        List<String> columns,
        Set<String> claimedColumns,
        @Nullable String prefix
    ) {
        var prefixPartCount = prefix != null ? namingStyle.split(prefix).length : 0;
        return columns.stream()
            .filter(col -> extractAttributeName(col, prefixPartCount).equals(attributeName))
            .findFirst()
            .map(col -> {
                claimedColumns.add(col);
                return col;
            });
    }
}
