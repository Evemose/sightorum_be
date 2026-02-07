package com.rorm.dataimport.hierarchical;

import com.rorm.dataimport.hierarchical.HierarchicalStructure.DetectedRoot;

import java.util.List;
import java.util.Map;

/**
 * Context for field transformation operations, passed via ScopedValue.
 */
record TransformationContext(
    String dataSourceName,
    Map<String, DetectedRoot> newRoots,
    Map<String, DetectedRoot> originalRoots,
    List<HierarchicalOverride> overrides
) {}
