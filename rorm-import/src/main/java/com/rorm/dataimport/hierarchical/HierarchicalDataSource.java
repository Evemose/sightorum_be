package com.rorm.dataimport.hierarchical;

import com.rorm.dataimport.source.ImportDataSource;

/**
 * Extension of ImportDataSource for hierarchical data formats (JSON, YAML).
 * <p>
 * Hierarchical sources flatten themselves to the standard flat Map&lt;String, String&gt;
 * representation required by ImportDataSource. The key difference is that they can
 * organically detect their schema structure from the hierarchical data itself,
 * rather than requiring explicit column definitions or overrides.
 * <p>
 * The flattening follows these conventions:
 * <ul>
 *   <li>Nested objects become prefixed columns: {@code address.city}, {@code address.street}</li>
 *   <li>Arrays of primitives become separator-joined strings</li>
 *   <li>Arrays of objects indicate separate roots (one-to-many relationships)</li>
 * </ul>
 */
public interface HierarchicalDataSource extends ImportDataSource {

    /**
     * Returns the detected schema structure from the hierarchical data.
     * This is extracted organically from the structure itself - roots, composites,
     * and relationships are inferred from nesting and array patterns.
     * <p>
     * Unlike flat data sources where structure must be guessed from column names
     * and overrides, hierarchical sources provide definitive structural information.
     */
    HierarchicalStructure detectStructure();
}
