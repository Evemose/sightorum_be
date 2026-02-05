package com.rorm.ai;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Provides runtime schema metadata to enrich AI prompts with database statistics.
 *
 * <p>When provided to {@link MetamodelContextBuilder}, this enables the inclusion of
 * helpful metadata like approximate row counts, common values, and index information
 * that helps the AI agent make better query decisions.
 */
public interface SchemaMetadataProvider {

    /**
     * Get metadata for a specific table.
     *
     * @param tableName the name of the table
     * @return metadata for the table, or null if not available
     */
    @Nullable
    TableMetadata getMetadata(String tableName);

    /**
     * Metadata about a database table.
     *
     * @param approximateRowCount Approximate number of rows in the table.
     *                            This should be a fast estimate (e.g., from pg_stat_user_tables),
     *                            not an exact count which could be expensive.
     * @param indexedColumns      Optional list of indexed columns.
     *                            Helps the AI understand which columns are efficient to filter on.
     * @param description         Optional description or business context for the table.
     */
    record TableMetadata(
        long approximateRowCount,
        @Nullable List<String> indexedColumns,
        @Nullable String description
    ) {
        public TableMetadata(long approximateRowCount) {
            this(approximateRowCount, null, null);
        }
    }
}
