package com.rorm.dataimport.attribute;

import com.rorm.dataimport.naming.NamingStyle;
import com.rorm.dataimport.override.SchemaOverride;

import java.util.*;

public class AttributeTypeDetector {

    private final List<AttributeDetectionHandler> handlerChain;

    public AttributeTypeDetector(
        Set<String> availableRootNames,
        NamingStyle namingStyle,
        List<SchemaOverride> overrides,
        String defaultListSeparator
    ) {
        this.handlerChain = buildHandlerChain(
            availableRootNames,
            namingStyle,
            overrides,
            defaultListSeparator
        );
    }

    private List<AttributeDetectionHandler> buildHandlerChain(
        Set<String> availableRootNames,
        NamingStyle namingStyle,
        List<SchemaOverride> overrides,
        String defaultListSeparator
    ) {
        return List.of(
            new ExplicitOverrideHandler(namingStyle, overrides, defaultListSeparator),
            new CompositeAttributeHandler(availableRootNames, namingStyle, overrides),
            new ReferenceAttributeHandler(availableRootNames, namingStyle),
            new BasicAttributeHandler(namingStyle)
        );
    }

    public Map<String, DetectedAttribute> detectAttributes(List<String> columnNames) {
        var result = new LinkedHashMap<String, DetectedAttribute>();
        var remainingColumns = new ArrayList<>(columnNames);

        for (var handler : handlerChain) {
            var claimedColumns = handler.handle(remainingColumns, result);
            remainingColumns.removeAll(claimedColumns);
        }

        return result;
    }
}
