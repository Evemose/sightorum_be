package com.rorm.query;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * A Common Table Expression (CTE) for use in WITH clauses.
 *
 * @param name    the CTE name (used as a table alias in the main query)
 * @param query   the CTE query definition
 * @param columns optional explicit column aliases for the CTE
 */
public record CteDefinition(
    String name,
    Query query,
    @Nullable List<String> columns
) {

    /**
     * Convenience constructor without explicit column aliases.
     */
    public CteDefinition(String name, Query query) {
        this(name, query, null);
    }

    public static CteDefinition of(String name, Query query) {
        return new CteDefinition(name, query);
    }

    public static CteDefinition of(String name, Query query, List<String> columns) {
        return new CteDefinition(name, query, columns);
    }
}
