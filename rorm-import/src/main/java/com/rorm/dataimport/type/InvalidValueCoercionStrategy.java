package com.rorm.dataimport.type;

import org.jspecify.annotations.Nullable;

/**
 * Top-level sealed interface for all coercion strategies.
 * Coercion can happen in two phases:
 * <ul>
 *   <li>{@link InMemoryCoercion} - Applied during import, row-by-row as data is read</li>
 *   <li>{@link DbLevelCoercion} - Applied after import, using database operations and aggregations</li>
 * </ul>
 *
 * <h3>Design Rationale</h3>
 * <p>In-memory coercions can handle simple transformations (skip, use default, throw) and
 * numeric adjustments (round, clamp, truncate) that don't require knowledge of other rows.</p>
 *
 * <p>Database-level coercions handle aggregations (forward fill, backward fill, mean imputation)
 * that require analyzing the entire dataset after initial import. These execute as SQL UPDATE
 * statements against the imported data.</p>
 */
public sealed interface InvalidValueCoercionStrategy permits InMemoryCoercion, DbLevelCoercion {

    /**
     * Process a string value for type detection phase.
     * Returns null if the value should be skipped during type inference.
     * Default implementation trims whitespace and returns null for empty strings.
     *
     * @param value Raw string value (may be null)
     * @return Processed value for type detection, or null to skip
     */
    @Nullable
    default String processForDetection(@Nullable String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        var trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

