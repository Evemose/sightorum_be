package com.rorm.fetcher;

import com.rorm.fetcher.converter.RowConversionException;

import java.util.Set;

/**
 * Converts a Row into a specific type.
 *
 * @param <T> the target type
 */
@FunctionalInterface
public interface RowConverter<T> {

    /**
     * Convert a row to the target type.
     *
     * @param row             the row to convert
     * @param consumedColumns a set to track which columns have been consumed
     * @return the converted value
     * @throws RowConversionException if conversion fails
     */
    T convert(Row row, Set<String> consumedColumns);
}
