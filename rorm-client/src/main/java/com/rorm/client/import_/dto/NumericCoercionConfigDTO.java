package com.rorm.client.import_.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.Map;

/**
 * DTO for NumericCoercionConfig - configures post-import aggregation strategies
 * for filling NULL values that resulted from numeric overflow.
 *
 * @param columnStrategies Map of table name -> column name -> aggregation strategy.
 *                         Example: {"users": {"age": "MEAN", "score": "MEDIAN"}}
 */
public record NumericCoercionConfigDTO(
    @Valid
    Map<String, Map<String, @Valid AggregationStrategyDTO>> columnStrategies
) {
    public NumericCoercionConfigDTO {
        columnStrategies = columnStrategies != null ? Map.copyOf(columnStrategies) : Map.of();
    }

    public enum AggregationStrategyDTO {
        /**
         * Fill NULLs with mean (average) of non-null values.
         */
        MEAN,

        /**
         * Fill NULLs with median of non-null values.
         */
        MEDIAN,

        /**
         * Fill NULLs with mode (most frequent value) of non-null values.
         */
        MODE,

        /**
         * Fill NULLs by forward-filling from previous non-null value.
         */
        FORWARD_FILL,

        /**
         * Fill NULLs with a constant value.
         */
        CONSTANT
    }

    /**
     * Configuration for CONSTANT strategy.
     */
    public record ConstantConfig(
        String tableName,
        String columnName,
        @NotNull BigDecimal value
    ) {}

    /**
     * Configuration for FORWARD_FILL strategy.
     */
    public record ForwardFillConfig(
        String tableName,
        String columnName,
        @NotNull String orderByColumn
    ) {}
}
