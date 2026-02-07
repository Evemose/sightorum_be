package com.rorm.dataimport.pipeline;

import java.util.HashMap;
import java.util.Map;

/**
 * Configuration for post-import aggregation to fill NULL values in numeric columns.
 * <p>
 * During import, values that exceed precision/scale constraints are set to NULL.
 * This config specifies which aggregation method to use for filling those NULLs.
 */
public class NumericCoercionConfig {

    private final Map<String, Map<String, AggregationStrategy>> tableColumnStrategies = new HashMap<>();
    private final Map<String, Map<String, Number>> constantValues = new HashMap<>();
    private final Map<String, Map<String, String>> forwardFillOrderColumns = new HashMap<>();

    public void setMeanForColumn(String tableName, String columnName) {
        setStrategy(tableName, columnName, AggregationStrategy.MEAN);
    }

    private void setStrategy(String tableName, String columnName, AggregationStrategy strategy) {
        tableColumnStrategies
            .computeIfAbsent(tableName, _ -> new HashMap<>())
            .put(columnName, strategy);
    }

    public void setMedianForColumn(String tableName, String columnName) {
        setStrategy(tableName, columnName, AggregationStrategy.MEDIAN);
    }

    public void setModeForColumn(String tableName, String columnName) {
        setStrategy(tableName, columnName, AggregationStrategy.MODE);
    }

    public void setForwardFillForColumn(String tableName, String columnName, String orderByColumn) {
        setStrategy(tableName, columnName, AggregationStrategy.FORWARD_FILL);
        forwardFillOrderColumns
            .computeIfAbsent(tableName, _ -> new HashMap<>())
            .put(columnName, orderByColumn);
    }

    public void setConstantForColumn(String tableName, String columnName, Number value) {
        setStrategy(tableName, columnName, AggregationStrategy.CONSTANT);
        constantValues
            .computeIfAbsent(tableName, _ -> new HashMap<>())
            .put(columnName, value);
    }

    public enum AggregationStrategy {
        MEAN,
        MEDIAN,
        MODE,
        FORWARD_FILL,
        CONSTANT
    }
}
