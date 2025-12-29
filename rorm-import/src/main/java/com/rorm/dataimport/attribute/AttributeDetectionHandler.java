package com.rorm.dataimport.attribute;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Handler in the chain of responsibility for detecting attributes from CSV columns.
 * Each handler processes columns it can handle and returns the set of claimed columns.
 */
@FunctionalInterface
interface AttributeDetectionHandler {

    /**
     * Process columns and detect attributes.
     *
     * @param columnNames columns available for processing (not yet claimed)
     * @param result      map to add detected attributes to
     * @return set of column names claimed by this handler
     */
    Set<String> handle(List<String> columnNames, Map<String, DetectedAttribute> result);
}
