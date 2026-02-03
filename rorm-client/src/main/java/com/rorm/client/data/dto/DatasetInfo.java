package com.rorm.client.data.dto;

import java.util.List;

public record DatasetInfo(
    String schemaName,
    List<TableInfo> tables,
    long totalRows
) {
    public record TableInfo(
        String tableName,
        long rowCount,
        List<String> columns
    ) {}
}
