package com.rorm.dataimport.attribute;

import com.rorm.dataimport.naming.NamingStyle;

import java.util.*;

class ReferenceAttributeHandler implements AttributeDetectionHandler {

    private final Set<String> availableRootNames;
    private final NamingStyle namingStyle;

    ReferenceAttributeHandler(Set<String> availableRootNames, NamingStyle namingStyle) {
        this.availableRootNames = availableRootNames;
        this.namingStyle = namingStyle;
    }

    @Override
    public Set<String> handle(List<String> columnNames, Map<String, DetectedAttribute> result) {
        var claimedColumns = new HashSet<String>();
        columnNames.stream()
            .map(this::detectReference)
            .flatMap(Optional::stream)
            .forEach(ref -> {
                result.put(ref.name(), ref);
                claimedColumns.add(switch (ref) {
                    case DetectedAttribute.SingularReference sr -> sr.columnName();
                    case DetectedAttribute.PluralReference pr -> pr.columnName();
                    default -> throw new IllegalStateException("Unexpected reference type: " + ref);
                });
            });
        return claimedColumns;
    }

    private Optional<DetectedAttribute> detectReference(String column) {
        var parts = namingStyle.split(column);
        var lastPart = parts[parts.length - 1].toLowerCase();

        if (lastPart.equals("id") && parts.length > 1) {
            var rootNameParts = Arrays.copyOf(parts, parts.length - 1);
            var rootName = String.join(namingStyle.getSeparator(), rootNameParts);

            if (availableRootNames.contains(rootName)) {
                var attrName = NamingStyle.toCamelCase(parts);
                return Optional.of(new DetectedAttribute.SingularReference(attrName, column, rootName));
            }
        }

        if (lastPart.equals("ids") && parts.length > 1) {
            var rootNameParts = Arrays.copyOf(parts, parts.length - 1);
            var rootName = String.join(namingStyle.getSeparator(), rootNameParts);

            if (availableRootNames.contains(rootName)) {
                var attrName = NamingStyle.toCamelCase(parts);
                return Optional.of(new DetectedAttribute.PluralReference(attrName, column, rootName));
            }
        }

        return Optional.empty();
    }
}
