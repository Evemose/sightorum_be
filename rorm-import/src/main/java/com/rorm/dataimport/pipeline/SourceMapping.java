package com.rorm.dataimport.pipeline;

/**
 * Tracks the source location of an attribute's data in an ImportDataSource.
 * This mapping is established at detection time and used during import.
 *
 * @param dataSourceName Name of the ImportDataSource (e.g., filename without extension)
 * @param sourceColumn   Column name in the source data (e.g., CSV column header)
 */
public record SourceMapping(
    String dataSourceName,
    String sourceColumn
) {
}
