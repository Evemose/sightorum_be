package com.rorm.dataimport.attribute;

import com.rorm.dataimport.naming.NamingStyle;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

class BasicAttributeHandler implements AttributeDetectionHandler {

    private final NamingStyle namingStyle;

    BasicAttributeHandler(NamingStyle namingStyle) {
        this.namingStyle = namingStyle;
    }

    @Override
    public Set<String> handle(List<String> columnNames, Map<String, DetectedAttribute> result) {
        var claimedColumns = new HashSet<>(columnNames);
        columnNames.forEach(column -> {
            var parts = namingStyle.split(column);
            var attrName = NamingStyle.toCamelCase(parts);
            result.put(attrName, new DetectedAttribute.Basic(attrName, column));
        });
        return claimedColumns;
    }
}
