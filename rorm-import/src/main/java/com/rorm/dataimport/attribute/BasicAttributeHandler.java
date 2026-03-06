package com.rorm.dataimport.attribute;

import com.rorm.dataimport.naming.NamingStyle;
import com.rorm.dataimport.pipeline.SourceMapping;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

class BasicAttributeHandler implements AttributeDetectionHandler {

    private final NamingStyle namingStyle;
    private final String dataSourceName;

    BasicAttributeHandler(NamingStyle namingStyle, String dataSourceName) {
        this.namingStyle = namingStyle;
        this.dataSourceName = dataSourceName;
    }

    @Override
    public Set<String> handle(List<String> columnNames, Map<String, DetectedAttribute> result) {
        var claimedColumns = new HashSet<>(columnNames);
        columnNames.forEach(column -> {
            var parts = namingStyle.split(column);
            var attrName = NamingStyle.CAMEL_CASE.join(parts);
            var source = new SourceMapping(dataSourceName, column);
            result.put(attrName, new DetectedAttribute.Basic(attrName, source, null));
        });
        return claimedColumns;
    }
}
