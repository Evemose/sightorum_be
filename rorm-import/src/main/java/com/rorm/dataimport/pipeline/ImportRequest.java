package com.rorm.dataimport.pipeline;

import com.rorm.dataimport.source.ImportDataSource;

import java.util.List;

/**
 * Request object for data import operations.
 *
 * @param targetSchema   The database schema to import into
 * @param dataSources    The data sources to import from
 * @param detectedSchema The detected schema with source mappings for multi-root support
 * @param chunkSize      The batch size for import operations
 */
public record ImportRequest(
    String targetSchema,
    List<ImportDataSource> dataSources,
    DetectedSchema detectedSchema,
    int chunkSize
) {
    public ImportRequest(String targetSchema, List<ImportDataSource> dataSources, DetectedSchema detectedSchema) {
        this(targetSchema, dataSources, detectedSchema, 1000);
    }

    public ImportRequest {
        chunkSize = chunkSize > 0 ? chunkSize : 1000;
    }
}
