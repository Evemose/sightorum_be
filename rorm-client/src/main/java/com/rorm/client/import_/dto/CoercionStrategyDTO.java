package com.rorm.client.import_.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Sealed hierarchy for coercion strategy DTOs.
 * Provides type-safe, clean API without parameter hell or null returns.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = CoercionStrategyDTO.SkipDTO.class, name = "SKIP"),
    @JsonSubTypes.Type(value = CoercionStrategyDTO.UseDefaultDTO.class, name = "USE_DEFAULT"),
    @JsonSubTypes.Type(value = CoercionStrategyDTO.NullOnInvalidDTO.class, name = "NULL_ON_INVALID"),
    @JsonSubTypes.Type(value = CoercionStrategyDTO.ThrowOnInvalidDTO.class, name = "THROW_ON_INVALID"),
    @JsonSubTypes.Type(value = CoercionStrategyDTO.RoundDTO.class, name = "ROUND"),
    @JsonSubTypes.Type(value = CoercionStrategyDTO.ClampDTO.class, name = "CLAMP"),
    @JsonSubTypes.Type(value = CoercionStrategyDTO.TruncateDTO.class, name = "TRUNCATE"),
    @JsonSubTypes.Type(value = CoercionStrategyDTO.ForwardFillDTO.class, name = "FORWARD_FILL"),
    @JsonSubTypes.Type(value = CoercionStrategyDTO.BackwardFillDTO.class, name = "BACKWARD_FILL"),
    @JsonSubTypes.Type(value = CoercionStrategyDTO.UseMeanDTO.class, name = "USE_MEAN"),
    @JsonSubTypes.Type(value = CoercionStrategyDTO.UseMedianDTO.class, name = "USE_MEDIAN"),
    @JsonSubTypes.Type(value = CoercionStrategyDTO.UseModeDTO.class, name = "USE_MODE")
})
public sealed interface CoercionStrategyDTO {

    // ==================== In-Memory Coercion Strategies ====================

    /**
     * Skip invalid values - set them to NULL in the database.
     */
    record SkipDTO() implements CoercionStrategyDTO {}

    /**
     * Use default values for invalid data based on data type.
     *
     * @param useStandardDefaults If true, use standard defaults (0 for numbers, empty string for text).
     *                            If false, use NULL for all types.
     */
    record UseDefaultDTO(boolean useStandardDefaults) implements CoercionStrategyDTO {
        public UseDefaultDTO() {
            this(true);
        }
    }

    /**
     * Return NULL for invalid values (including overflow, parse errors, etc.).
     */
    record NullOnInvalidDTO() implements CoercionStrategyDTO {}

    /**
     * Throw an exception when invalid values are encountered.
     */
    record ThrowOnInvalidDTO() implements CoercionStrategyDTO {}

    /**
     * Round numeric values with configurable rounding mode.
     *
     * @param roundingMode The rounding mode to use (defaults to HALF_UP)
     */
    record RoundDTO(@NotNull RoundingMode roundingMode) implements CoercionStrategyDTO {
        public RoundDTO() {
            this(RoundingMode.HALF_UP);
        }
    }

    /**
     * Clamp numeric values to specified bounds or percentage range [0, 1].
     *
     * @param minBound Optional minimum bound (null = use data type minimum)
     * @param maxBound Optional maximum bound (null = use data type maximum)
     */
    record ClampDTO(
        @Nullable
        @DecimalMin(value = "-999999999999999999")
        BigDecimal minBound,

        @Nullable
        @DecimalMax(value = "999999999999999999")
        BigDecimal maxBound
    ) implements CoercionStrategyDTO {

        /**
         * Factory method for percentage clamping [0, 1].
         */
        public static ClampDTO percentage() {
            return new ClampDTO(BigDecimal.ZERO, BigDecimal.ONE);
        }
    }

    /**
     * Truncate numeric decimal values.
     */
    record TruncateDTO() implements CoercionStrategyDTO {}

    // ==================== Database-Level Coercion Strategies ====================

    /**
     * Forward fill - propagate last valid value forward to fill NULLs.
     * Executes after import using SQL.
     */
    record ForwardFillDTO() implements CoercionStrategyDTO {}

    /**
     * Backward fill - propagate next valid value backward to fill NULLs.
     * Executes after import using SQL.
     */
    record BackwardFillDTO() implements CoercionStrategyDTO {}

    /**
     * Use mean for numeric columns, mode for categorical/string columns.
     * Executes after import using SQL.
     */
    record UseMeanDTO() implements CoercionStrategyDTO {}

    /**
     * Use median value for numeric columns.
     * Executes after import using SQL.
     */
    record UseMedianDTO() implements CoercionStrategyDTO {}

    /**
     * Use mode (most frequent value) for categorical columns.
     * Executes after import using SQL.
     */
    record UseModeDTO() implements CoercionStrategyDTO {}
}
