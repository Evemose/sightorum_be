package com.rorm.client.data.dto;

import java.util.List;
import java.util.Set;

public record DatasetInfo(
    String schemaName,
    List<TableInfo> tables,
    long totalRows
) {
    /**
     * `summaryFlags` and `rowCountSource` come from the pre-computed
     * {@code SchemaProfile} when one exists for this schema. When the
     * profile is absent (e.g. schemas imported before the analyzer
     * shipped, or analyzer failures) row count falls back to a live
     * {@code SELECT COUNT(*)}; that's reflected in
     * {@code rowCountSource = "LIVE"}.
     */
    public record TableInfo(
        String tableName,
        long rowCount,
        List<String> columns,
        Set<String> summaryFlags,
        String rowCountSource
    ) {
        public TableInfo(String tableName, long rowCount, List<String> columns) {
            this(tableName, rowCount, columns, Set.of(), "LIVE");
        }
    }
}
