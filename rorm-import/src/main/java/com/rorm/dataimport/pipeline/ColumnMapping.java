package com.rorm.dataimport.pipeline;

import com.rorm.metamodel.DataType;
import org.jspecify.annotations.Nullable;

/**
 * Maps a database column to its source column in an ImportDataSource.
 *
 * @param dbColumnName     Name of the column in the database table
 * @param sourceColumn     Name of the column in the source data (e.g., CSV column header)
 * @param sourceDataSource Name of the ImportDataSource where this data comes from (null if same as current)
 * @param dataType         Data type for parsing/conversion
 */
record ColumnMapping(
    String dbColumnName,
    String sourceColumn,
    @Nullable String sourceDataSource,
    DataType dataType,
    @Nullable String collectionSeparator
) {
    ColumnMapping(
        String dbColumnName,
        String sourceColumn,
        @Nullable String sourceDataSource,
        DataType dataType
    ) {
        this(dbColumnName, sourceColumn, sourceDataSource, dataType, null);
    }

    /**
     * Constructor for backward compatibility when source is the same datasource.
     */
    ColumnMapping(String dbColumnName, String sourceColumn, DataType dataType) {
        this(dbColumnName, sourceColumn, null, dataType, null);
    }
}
