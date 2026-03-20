package com.rorm.dataimport.type;

import com.rorm.metamodel.DataType;
import com.rorm.metamodel.DataType.CategorcialType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DataTypeDetector Tests")
class DataTypeDetectorTest {

    private final DataTypeDetector detector = new DataTypeDetector();
    private final InvalidValueCoercionStrategy skipNulls = InMemoryCoercion.Skip.INSTANCE;
    private final InMemoryCoercion treatAsString = new InMemoryCoercion.UseDefault("");

    // ==================== Boolean Type Tests ====================

    @Test
    @DisplayName("detect boolean type from true/false values")
    void detectBooleanTrueFalse() {
        var samples = List.of("true", "false", "true", "false");
        var result = detector.detectType(samples, skipNulls);
        assertThat(result).isInstanceOf(DataType.BooleanType.class);
    }

    @Test
    @DisplayName("detect boolean type from T/F values")
    void detectBooleanTF() {
        var samples = List.of("T", "F", "T", "F");
        var result = detector.detectType(samples, skipNulls);
        assertThat(result).isInstanceOf(DataType.BooleanType.class);
    }

    @Test
    @DisplayName("detect boolean type from yes/no values")
    void detectBooleanYesNo() {
        var samples = List.of("yes", "no", "yes", "no");
        var result = detector.detectType(samples, skipNulls);
        assertThat(result).isInstanceOf(DataType.BooleanType.class);
    }

    @Test
    @DisplayName("0/1 values detected as numeric, not boolean")
    void zeroOneDetectedAsNumeric() {
        var samples = List.of("0", "1", "0", "1");
        var result = detector.detectType(samples, skipNulls);
        assertThat(result).isNotInstanceOf(DataType.BooleanType.class);
    }

    @Test
    @DisplayName("detect boolean type case-insensitive")
    void detectBooleanCaseInsensitive() {
        var samples = List.of("TRUE", "False", "true", "false");
        var result = detector.detectType(samples, skipNulls);
        assertThat(result).isInstanceOf(DataType.BooleanType.class);
    }

    // ==================== Numeric Type Tests ====================

    @Test
    @DisplayName("detect numeric type from integer values")
    void detectNumericInteger() {
        var samples = List.of("123", "456", "789", "0");
        var result = detector.detectType(samples, skipNulls);
        assertThat(result).isInstanceOf(DataType.NumericType.class);
        var numericType = (DataType.NumericType) result;
        assertThat(numericType.precision()).isGreaterThanOrEqualTo(3);
        assertThat(numericType.scale()).isEqualTo(0);
    }

    @Test
    @DisplayName("detect numeric type from decimal values")
    void detectNumericDecimal() {
        var samples = List.of("123.45", "67.89", "0.123");
        var result = detector.detectType(samples, skipNulls);
        assertThat(result).isInstanceOf(DataType.NumericType.class);
        var numericType = (DataType.NumericType) result;
        assertThat(numericType.precision()).isGreaterThanOrEqualTo(5);
        assertThat(numericType.scale()).isGreaterThan(0);
    }

    @Test
    @DisplayName("detect numeric type with negative values")
    void detectNumericNegative() {
        var samples = List.of("-123", "-456.78", "123", "0");
        var result = detector.detectType(samples, skipNulls);
        assertThat(result).isInstanceOf(DataType.NumericType.class);
    }

    @Test
    @DisplayName("numeric type tracks max precision")
    void detectNumericMaxPrecision() {
        var samples = List.of("1", "12", "123", "1234");
        var result = detector.detectType(samples, skipNulls);
        assertThat(result).isInstanceOf(DataType.NumericType.class);
        var numericType = (DataType.NumericType) result;
        assertThat(numericType.precision()).isGreaterThanOrEqualTo(4);
    }

    // ==================== DateTime Type Tests ====================

    @Test
    @DisplayName("detect datetime type from ISO datetime")
    void detectDateTimeISO() {
        var samples = List.of("2024-01-15T14:30:00", "2024-02-20T16:45:00");
        var result = detector.detectType(samples, skipNulls);
        assertThat(result).isInstanceOf(DataType.DateTimeType.class);
    }

    @Test
    @DisplayName("detect datetime type from standard format")
    void detectDateTimeStandard() {
        var samples = List.of("2024-01-15 14:30:00", "2024-02-20 16:45:00");
        var result = detector.detectType(samples, skipNulls);
        assertThat(result).isInstanceOf(DataType.DateTimeType.class);
    }

    // ==================== Date Type Tests ====================

    @Test
    @DisplayName("detect date type from ISO format")
    void detectDateISO() {
        var samples = List.of("2024-01-15", "2024-02-20", "2024-03-10");
        var result = detector.detectType(samples, skipNulls);
        assertThat(result).isInstanceOf(DataType.DateType.class);
    }

    @Test
    @DisplayName("detect date type from slash format")
    void detectDateSlash() {
        var samples = List.of("2024/01/15", "2024/02/20", "2024/03/10");
        var result = detector.detectType(samples, skipNulls);
        assertThat(result).isInstanceOf(DataType.DateType.class);
    }

    // ==================== Time Type Tests ====================

    @Test
    @DisplayName("detect time type from HH:mm:ss format")
    void detectTimeWithSeconds() {
        var samples = List.of("14:30:00", "16:45:30", "08:15:45");
        var result = detector.detectType(samples, skipNulls);
        assertThat(result).isInstanceOf(DataType.TimeType.class);
    }

    @Test
    @DisplayName("detect time type from HH:mm format")
    void detectTimeWithoutSeconds() {
        var samples = List.of("14:30", "16:45", "08:15");
        var result = detector.detectType(samples, skipNulls);
        assertThat(result).isInstanceOf(DataType.TimeType.class);
    }

    // ==================== DayOfWeek Type Tests ====================

    @Test
    @DisplayName("detect day of week type")
    void detectDayOfWeek() {
        var samples = List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY");
        var result = detector.detectType(samples, skipNulls);
        assertThat(result).isInstanceOf(DataType.DayOfWeekType.class);
    }

    @Test
    @DisplayName("detect day of week type lowercase")
    void detectDayOfWeekLowercase() {
        var samples = List.of("monday", "tuesday", "wednesday");
        var result = detector.detectType(samples, skipNulls);
        assertThat(result).isInstanceOf(DataType.DayOfWeekType.class);
    }

    // ==================== Timezone Type Tests ====================

    @Test
    @DisplayName("detect timezone type from zone IDs")
    void detectTimezoneZoneId() {
        var samples = List.of("America/New_York", "Europe/London", "Asia/Tokyo");
        var result = detector.detectType(samples, skipNulls);
        assertThat(result).isInstanceOf(DataType.TimezoneType.class);
    }

    @Test
    @DisplayName("detect timezone type from zone offsets")
    void detectTimezoneZoneOffset() {
        var samples = List.of("+01:00", "-05:00", "+09:00", "Z");
        var result = detector.detectType(samples, skipNulls);
        assertThat(result).isInstanceOf(DataType.TimezoneType.class);
    }

    // ==================== Enum Type Tests ====================

    @Test
    @DisplayName("detect enum type with few distinct values")
    void detectEnumFewValues() {
        var samples = List.of("RED", "GREEN", "BLUE", "RED", "GREEN", "BLUE", "RED", "GREEN", "BLUE");
        var result = detector.detectType(samples, skipNulls);
        assertThat(result).isInstanceOf(CategorcialType.class);
        var enumType = (CategorcialType) result;
        assertThat(enumType.values()).containsExactlyInAnyOrder("RED", "GREEN", "BLUE");
    }

    @Test
    @DisplayName("not detect enum with too many distinct values")
    void notDetectEnumTooManyValues() {
        var samples = List.of(
            "V1", "V2", "V3", "V4", "V5", "V6", "V7", "V8", "V9", "V10",
            "V11", "V12", "V13", "V14", "V15", "V16", "V17", "V18", "V19", "V20",
            "V21", "V22"
        );
        var result = detector.detectType(samples, skipNulls);
        assertThat(result).isInstanceOf(DataType.StringType.class);
    }

    @Test
    @DisplayName("not detect enum with insufficient repetitions")
    void notDetectEnumInsufficientRepetitions() {
        var samples = List.of("RED", "GREEN", "BLUE");
        var result = detector.detectType(samples, skipNulls);
        assertThat(result).isInstanceOf(DataType.StringType.class);
    }

    // ==================== String Type Tests (Fallback) ====================

    @Test
    @DisplayName("fallback to string type for mixed values")
    void fallbackToString() {
        var samples = List.of("hello", "123", "true", "2024-01-01");
        var result = detector.detectType(samples, skipNulls);
        assertThat(result).isInstanceOf(DataType.StringType.class);
    }

    @Test
    @DisplayName("fallback to string type for arbitrary text")
    void fallbackToStringArbitraryText() {
        var samples = List.of("Hello World", "Foo Bar", "Baz Qux");
        var result = detector.detectType(samples, skipNulls);
        assertThat(result).isInstanceOf(DataType.StringType.class);
    }

    // ==================== Null Handling Tests ====================

    @Test
    @DisplayName("skip nulls strategy ignores empty values")
    void skipNullsIgnoresEmpty() {
        var samples = List.of("123", "", "456", "789");
        var result = detector.detectType(samples, skipNulls);
        assertThat(result).isInstanceOf(DataType.NumericType.class);
    }

    @Test
    @DisplayName("treat as string strategy handles empty values as strings")
    void treatAsStringHandlesEmpty() {
        var samples = List.of("", "hello", "world");
        var result = detector.detectType(samples, treatAsString);
        assertThat(result).isInstanceOf(DataType.StringType.class);
    }

    @Test
    @DisplayName("empty collection returns string type")
    void emptyCollectionReturnsString() {
        var samples = List.of("", "", "", "");
        var result = detector.detectType(samples, skipNulls);
        assertThat(result).isInstanceOf(DataType.StringType.class);
    }

    // ==================== Priority Tests ====================

    @Test
    @DisplayName("boolean has priority over categorical for true/false")
    void booleanPriorityOverCategorical() {
        var samples = List.of("true", "false", "true", "false", "true", "false");
        var result = detector.detectType(samples, skipNulls);
        assertThat(result).isInstanceOf(DataType.BooleanType.class);
    }

    @Test
    @DisplayName("datetime has priority over date for datetime values")
    void datetimePriorityOverDate() {
        var samples = List.of("2024-01-15T14:30:00", "2024-02-20T16:45:00");
        var result = detector.detectType(samples, skipNulls);
        assertThat(result).isInstanceOf(DataType.DateTimeType.class);
    }
}
