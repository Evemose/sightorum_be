package com.rorm.client.data.dto;

import java.util.List;
import java.util.Map;

public record QueryResponse(
    List<Map<String, Object>> rows,
    long totalRows,
    long executionTimeMs
) {}
