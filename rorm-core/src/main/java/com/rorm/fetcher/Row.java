package com.rorm.fetcher;

import org.jspecify.annotations.Nullable;

/**
 * Abstraction over a result row that provides column access by name.
 */
public interface Row {

    /**
     * Get a column value by name.
     *
     * @param columnName the column name
     * @return the value, or null if not present
     */
    @Nullable
    Object get(String columnName);

    /**
     * Get a column value by name with type assertion and cast.
     *
     * @param columnName the column name
     * @param type       the expected type
     * @return the value cast to the specified type, or null if not present
     * @throws ClassCastException if the value cannot be cast to the specified type
     */
    @Nullable
    <T> T get(String columnName, Class<T> type);

    /**
     * Get all column names in this row.
     *
     * @return array of column names
     */
    String[] getColumnNames();

    /**
     * Check if a column exists in this row.
     *
     * @param columnName the column name
     * @return true if the column exists
     */
    boolean hasColumn(String columnName);
}
