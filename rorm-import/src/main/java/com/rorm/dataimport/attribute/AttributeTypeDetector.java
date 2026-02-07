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
        String defaultListSeparator,
        String dataSourceName,
        String currentRootName
    ) {
        this.handlerChain = buildHandlerChain(
            availableRootNames,
            namingStyle,
            overrides,
            defaultListSeparator,
            dataSourceName,
            currentRootName
        );
    }

    private List<AttributeDetectionHandler> buildHandlerChain(
        Set<String> availableRootNames,
        NamingStyle namingStyle,
        List<SchemaOverride> overrides,
        String defaultListSeparator,
        String dataSourceName,
        String currentRootName
    ) {
        return List.of(
            new ExplicitOverrideHandler(namingStyle, overrides, defaultListSeparator, dataSourceName),
            new CompositeAttributeHandler(availableRootNames, namingStyle, overrides, dataSourceName),
            new ReferenceAttributeHandler(availableRootNames, namingStyle, dataSourceName, currentRootName),
            new BasicAttributeHandler(namingStyle, dataSourceName)
        );
    }

    public Map<String, DetectedAttribute> detectAttributes(List<String> columnNames) {
        // Column names are already normalized by the data source
        var result = new LinkedHashMap<String, DetectedAttribute>();
        var remainingColumns = new ArrayList<>(columnNames);

        for (var handler : handlerChain) {
            var claimedColumns = handler.handle(remainingColumns, result);
            remainingColumns.removeAll(claimedColumns);
        }

        return result;
    }
}
