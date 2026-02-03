package com.rorm.client.data.dto;

import java.util.List;
import java.util.Map;

public record SampleResponse(
    String schemaName,
    String tableName,
    List<String> columns,
    List<Map<String, Object>> rows,
    long totalRows
) {}
