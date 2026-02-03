package com.rorm.dataimport.attribute;

import com.rorm.dataimport.naming.NamingStyle;
import com.rorm.dataimport.pipeline.SourceMapping;

import java.util.*;

class ReferenceAttributeHandler implements AttributeDetectionHandler {

    private final Set<String> availableRootNames;
    private final NamingStyle namingStyle;
    private final String dataSourceName;

    ReferenceAttributeHandler(Set<String> availableRootNames, NamingStyle namingStyle, String dataSourceName) {
        this.availableRootNames = availableRootNames;
        this.namingStyle = namingStyle;
        this.dataSourceName = dataSourceName;
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
                    case DetectedAttribute.SingularReference sr -> sr.source().sourceColumn();
                    case DetectedAttribute.PluralReference pr -> pr.source().sourceColumn();
                    default -> throw new IllegalStateException("Unexpected reference type: " + ref);
                });
            });
        return claimedColumns;
    }

    private Optional<DetectedAttribute> detectReference(String column) {
        return this.<DetectedAttribute>detectReference(
            column,
            "id",
            (attrName, source, targetRootName) -> new DetectedAttribute.SingularReference(
                attrName,
                source,
                targetRootName,
                null
            )
        ).or(() -> detectReference(
            column,
            "ids",
            (attrName, source, targetRootName) -> new DetectedAttribute.PluralReference(
                attrName,
                source,
                targetRootName,
                null
            )
        ));
    }

    private <T extends DetectedAttribute> Optional<T> detectReference(
        String column,
        String expectedLastPart,
        ReferenceFactory<T> factory
    ) {
        var parts = namingStyle.split(column);
        var lastPart = parts[parts.length - 1].toLowerCase();
        if (lastPart.equalsIgnoreCase(expectedLastPart) && parts.length > 1) {
            var rootNameParts = Arrays.copyOf(parts, parts.length - 1);
            var possiblySingularRootName = String.join(namingStyle.getSeparator(), rootNameParts);

            var rootOpt = availableRootNames.stream()
                .filter(rn -> NameUtils.singularize(rn).equalsIgnoreCase(possiblySingularRootName))
                .findFirst();
            if (rootOpt.isPresent()) {
                var attrName = NamingStyle.toCamelCase(parts);
                var source = new SourceMapping(dataSourceName, column);
                return Optional.of(factory.create(attrName, source, rootOpt.get()));
            }
        }
        return Optional.empty();
    }

    private interface ReferenceFactory<T extends DetectedAttribute> {
        T create(String attributeName, SourceMapping source, String targetRootName);
    }
}
