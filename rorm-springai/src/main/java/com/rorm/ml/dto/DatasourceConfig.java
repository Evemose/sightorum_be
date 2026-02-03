package com.rorm.ml.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.Map;

@JsonNaming(SnakeCaseStrategy.class)
public record DatasourceConfig(
    String sql,
    Map<String, Object> bindVariables
) {
    public DatasourceConfig {
        if (bindVariables == null) {
            bindVariables = Map.of();
        }
    }
}
