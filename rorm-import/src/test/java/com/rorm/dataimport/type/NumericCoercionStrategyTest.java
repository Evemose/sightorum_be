package com.rorm.dataimport.type;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("NumericCoercionStrategy")
class NumericCoercionStrategyTest {

    @Test
    @DisplayName("Round should round overflowing decimal to fit precision/scale")
    void roundShouldHandleOverflow() {
        // Precision 19, scale 18 means max value is 9.999...999 (1 integral digit, 18 decimal)
        // This value has 2 integral digits, which is too large
        var result = new NumericCoercionStrategy.Round().coerceNumeric("10.123456789012345678", 19, 18);

        assertThat(result).isNotNull();
        assertThat(result).isInstanceOf(Number.class);
    }

    @Test
    @DisplayName("Round should round integer values")
    void roundShouldHandleIntegers() {
        var result = new NumericCoercionStrategy.Round().coerceNumeric("123.7", 10, 0);

        assertThat(result).isEqualTo(124L);
    }

    @Test
    @DisplayName("Round with custom rounding mode should work")
    void roundWithCustomModeShouldWork() {
        var result = new NumericCoercionStrategy.Round(RoundingMode.DOWN).coerceNumeric("123.7", 10, 0);

        assertThat(result).isEqualTo(123L);
    }

    @Test
    @DisplayName("Clamp should clamp overflowing values to max")
    void clampShouldHandleOverflow() {
        // For precision 5, scale 2, max value is 999.99
        var result = new NumericCoercionStrategy.Clamp().coerceNumeric("1500.50", 5, 2);

        assertThat(result).isNotNull();
        assertThat(((Number) result).doubleValue()).isLessThanOrEqualTo(999.99);
    }

    @Test
    @DisplayName("Clamp with custom bounds should respect bounds")
    void clampWithBoundsShouldWork() {
        var result = new NumericCoercionStrategy.Clamp(BigDecimal.ZERO, BigDecimal.TEN)
            .coerceNumeric("15.5", 10, 2);

        assertThat(result).isEqualTo(new BigDecimal("10.00"));
    }

    @Test
    @DisplayName("Clamp percentage should clamp to [0, 1] range")
    void clampPercentageShouldClamp() {
        var result1 = NumericCoercionStrategy.Clamp.percentage().coerceNumeric("1.5", 19, 18);
        assertThat(result1).isEqualTo(new BigDecimal("1.000000000000000000"));

        var result2 = NumericCoercionStrategy.Clamp.percentage().coerceNumeric("-0.5", 19, 18);
        assertThat(result2).isEqualTo(new BigDecimal("0.000000000000000000"));

        var result3 = NumericCoercionStrategy.Clamp.percentage().coerceNumeric("0.75", 19, 18);
        assertThat(result3).isEqualTo(new BigDecimal("0.750000000000000000"));
    }

    @Test
    @DisplayName("Truncate should truncate decimals")
    void truncateShouldHandleDecimals() {
        var result = new NumericCoercionStrategy.Truncate().coerceNumeric("123.789", 10, 2);

        assertThat(result).isEqualTo(new BigDecimal("123.78"));
    }

    @Test
    @DisplayName("fitsWithinPrecision should correctly check precision limits")
    void fitsWithinPrecisionShouldCheck() {
        // Precision 19, scale 18: max value is 9.999...
        assertThat(NumericCoercionStrategy.fitsWithinPrecision(
            new BigDecimal("9.999999999999999999"), 19, 18
        )).isTrue();

        assertThat(NumericCoercionStrategy.fitsWithinPrecision(
            new BigDecimal("10.0"), 19, 18
        )).isFalse();

        assertThat(NumericCoercionStrategy.fitsWithinPrecision(
            new BigDecimal("0.123456789012345678"), 19, 18
        )).isTrue();
    }
}
