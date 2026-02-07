package com.rorm.dataimport.type;

import com.rorm.metamodel.DataType;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Strategies for coercing numeric values that don't fit within their target precision/scale.
 * Used during import to handle data quality issues like overflow, excessive precision, etc.
 * <p>
 * This is a specialized implementation of InMemoryCoercion for numeric overflow scenarios.
 * All numeric coercions execute in-memory during import row-by-row.
 */
public sealed interface NumericCoercionStrategy extends InMemoryCoercion permits
    NumericCoercionStrategy.Round,
    NumericCoercionStrategy.Clamp,
    NumericCoercionStrategy.Truncate {

    /**
     * Check if a BigDecimal fits within the given precision and scale.
     */
    static boolean fitsWithinPrecision(BigDecimal value, int precision, int scale) {
        var integralDigits = precision - scale;
        if (integralDigits <= 0) {
            return false;
        }

        var absValue = value.abs();
        var maxIntegralValue = BigDecimal.TEN.pow(integralDigits);

        return absValue.compareTo(maxIntegralValue) < 0;
    }

    /**
     * Clamp a value to the specified bounds or maximum representable value for precision/scale.
     */
    static Object clampValue(BigDecimal value, int precision, int scale,
                             @Nullable BigDecimal minBound, @Nullable BigDecimal maxBound) {
        var integralDigits = precision - scale;

        if (integralDigits <= 0) {
            return 0;
        }

        // Determine effective bounds
        BigDecimal effectiveMin, effectiveMax;

        if (minBound != null && maxBound != null) {
            // Use provided bounds
            effectiveMin = minBound;
            effectiveMax = maxBound;
        } else {
            // Calculate max absolute value for the integral part
            var maxIntegralValue = BigDecimal.TEN.pow(integralDigits).subtract(BigDecimal.ONE);

            // Build the maximum value with the given scale
            var maxValue = maxIntegralValue;
            if (scale > 0) {
                var fractionalPart = BigDecimal.ONE.subtract(BigDecimal.valueOf(1).scaleByPowerOfTen(-scale));
                maxValue = maxIntegralValue.add(fractionalPart);
            }

            effectiveMin = maxValue.negate();
            effectiveMax = maxValue;
        }

        // Apply bounds
        BigDecimal clamped;
        if (value.compareTo(effectiveMin) < 0) {
            clamped = effectiveMin;
        } else if (value.compareTo(effectiveMax) > 0) {
            clamped = effectiveMax;
        } else {
            clamped = value;
        }

        // Round to target scale
        clamped = clamped.setScale(scale, RoundingMode.HALF_UP);

        if (scale == 0) {
            return convertToAppropriateType(clamped, precision);
        }

        return clamped;
    }

    /**
     * Convert BigDecimal to appropriate integer type based on precision.
     */
    static Object convertToAppropriateType(BigDecimal value, int precision) {
        if (precision <= 4) {
            return value.shortValue();
        } else if (precision <= 9) {
            return value.intValue();
        } else {
            return value.longValue();
        }
    }

    /**
     * Implementation of InvalidValueCoercionStrategy interface.
     * Delegates to coerceNumeric for NumericType columns.
     */
    @Override
    default @Nullable Object coerce(@Nullable String value, DataType dataType, String columnName) {
        if (value == null || value.isEmpty()) {
            return null;
        }

        if (!(dataType instanceof DataType.NumericType(var precision, var scale))) {
            throw new IllegalArgumentException(
                "NumericCoercionStrategy can only be applied to NumericType columns, but got %s for column %s"
                    .formatted(dataType.getClass().getSimpleName(), columnName)
            );
        }

        return coerceNumeric(value, precision, scale);
    }

    /**
     * Coerce a numeric value to fit within the target precision and scale.
     *
     * @param value           String representation of the numeric value
     * @param targetPrecision Total number of significant digits
     * @param targetScale     Number of digits after decimal point
     * @return Coerced value as appropriate Java type (Long, Integer, Short, Double, or null)
     */
    Object coerceNumeric(String value, int targetPrecision, int targetScale);

    /**
     * Round to nearest value that fits within precision/scale constraints.
     */
    record Round(RoundingMode roundingMode) implements NumericCoercionStrategy {
        public Round() {
            this(RoundingMode.HALF_UP);
        }

        @Override
        public Object coerceNumeric(String value, int targetPrecision, int targetScale) {
            var decimal = new BigDecimal(value);

            // For integers (scale = 0), round to whole number
            if (targetScale == 0) {
                var rounded = decimal.setScale(0, roundingMode);
                return convertToAppropriateType(rounded, targetPrecision);
            }

            // For decimals, round to target scale
            var rounded = decimal.setScale(targetScale, roundingMode);

            // Check if value fits within precision constraint
            if (fitsWithinPrecision(rounded, targetPrecision, targetScale)) {
                return rounded;
            }

            // If still doesn't fit, clamp to max value
            return clampValue(rounded, targetPrecision, targetScale, null, null);
        }
    }

    /**
     * Clamp value to specified bounds or maximum representable value for the given precision/scale.
     */
    record Clamp(@Nullable BigDecimal minBound, @Nullable BigDecimal maxBound) implements NumericCoercionStrategy {

        public Clamp() {
            this(null, null);
        }

        /**
         * Clamp to [0, 1] range - useful for percentages/probabilities.
         */
        public static Clamp percentage() {
            return new Clamp(BigDecimal.ZERO, BigDecimal.ONE);
        }

        @Override
        public Object coerceNumeric(String value, int targetPrecision, int targetScale) {
            var decimal = new BigDecimal(value);
            return clampValue(decimal, targetPrecision, targetScale, minBound, maxBound);
        }
    }

    /**
     * Truncate to fit within precision/scale by removing least significant digits.
     */
    record Truncate() implements NumericCoercionStrategy {
        @Override
        public Object coerceNumeric(String value, int targetPrecision, int targetScale) {
            var decimal = new BigDecimal(value);

            if (targetScale == 0) {
                var truncated = decimal.setScale(0, RoundingMode.DOWN);
                return convertToAppropriateType(truncated, targetPrecision);
            }

            var truncated = decimal.setScale(targetScale, RoundingMode.DOWN);

            if (fitsWithinPrecision(truncated, targetPrecision, targetScale)) {
                return truncated;
            }

            return clampValue(truncated, targetPrecision, targetScale, null, null);
        }
    }
}
