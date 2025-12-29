package com.rorm.dataimport.type;

import com.rorm.metamodel.DataType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultValueStrategyTest {

    @Test
    @DisplayName("standard defaults returns BigDecimal.ZERO for numeric")
    void standardDefaultsNumeric() {
        var strategy = DefaultValueStrategy.standardDefaults();
        var result = strategy.getDefault("age", new DataType.NumericType(10, 0));

        assertThat(result).isEqualTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("standard defaults returns empty string for string type")
    void standardDefaultsString() {
        var strategy = DefaultValueStrategy.standardDefaults();
        var result = strategy.getDefault("name", new DataType.StringType());

        assertThat(result).isEqualTo("");
    }

    @Test
    @DisplayName("standard defaults returns false for boolean")
    void standardDefaultsBoolean() {
        var strategy = DefaultValueStrategy.standardDefaults();
        var result = strategy.getDefault("active", new DataType.BooleanType());

        assertThat(result).isEqualTo(Boolean.FALSE);
    }

    @Test
    @DisplayName("standard defaults returns LocalDate.EPOCH for date")
    void standardDefaultsDate() {
        var strategy = DefaultValueStrategy.standardDefaults();
        var result = strategy.getDefault("birth_date", new DataType.DateType());

        assertThat(result).isEqualTo(LocalDate.EPOCH);
    }

    @Test
    @DisplayName("standard defaults returns LocalTime.MIDNIGHT for time")
    void standardDefaultsTime() {
        var strategy = DefaultValueStrategy.standardDefaults();
        var result = strategy.getDefault("start_time", new DataType.TimeType());

        assertThat(result).isEqualTo(LocalTime.MIDNIGHT);
    }

    @Test
    @DisplayName("standard defaults returns Instant.EPOCH for datetime")
    void standardDefaultsDateTime() {
        var strategy = DefaultValueStrategy.standardDefaults();
        var result = strategy.getDefault("created_at", new DataType.DateTimeType());

        assertThat(result).isEqualTo(Instant.EPOCH);
    }

    @Test
    @DisplayName("standard defaults returns ZoneOffset.UTC for timezone")
    void standardDefaultsTimezone() {
        var strategy = DefaultValueStrategy.standardDefaults();
        var result = strategy.getDefault("timezone", new DataType.TimezoneType());

        assertThat(result).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    @DisplayName("standard defaults returns DayOfWeek.MONDAY for day of week")
    void standardDefaultsDayOfWeek() {
        var strategy = DefaultValueStrategy.standardDefaults();
        var result = strategy.getDefault("day", new DataType.DayOfWeekType());

        assertThat(result).isEqualTo(DayOfWeek.MONDAY);
    }

    @Test
    @DisplayName("standard defaults returns first enum value")
    void standardDefaultsEnum() {
        var strategy = DefaultValueStrategy.standardDefaults();
        var enumType = new DataType.EnumType(new String[]{"red", "green", "blue"});
        var result = strategy.getDefault("color", enumType);

        assertThat(result).isEqualTo("red");
    }

    @Test
    @DisplayName("standard defaults returns empty string for empty enum")
    void standardDefaultsEmptyEnum() {
        var strategy = DefaultValueStrategy.standardDefaults();
        var enumType = new DataType.EnumType(new String[]{});
        var result = strategy.getDefault("color", enumType);

        assertThat(result).isEqualTo("");
    }

    @Test
    @DisplayName("standard defaults returns empty string for list type")
    void standardDefaultsList() {
        var strategy = DefaultValueStrategy.standardDefaults();
        var listType = new DataType.ListType(new DataType.StringType());
        var result = strategy.getDefault("tags", listType);

        assertThat(result).isEqualTo("");
    }

    @Test
    @DisplayName("no defaults returns null for all types")
    void noDefaultsReturnsNull() {
        var strategy = DefaultValueStrategy.noDefaults();

        assertThat(strategy.getDefault("age", new DataType.NumericType(10, 0))).isNull();
        assertThat(strategy.getDefault("name", new DataType.StringType())).isNull();
        assertThat(strategy.getDefault("active", new DataType.BooleanType())).isNull();
    }

    @Test
    @DisplayName("column-specific default overrides type default")
    void columnDefaultOverridesType() {
        var strategy = DefaultValueStrategy.builder()
            .columnDefault("age", new BigDecimal("18"))
            .build();

        var result = strategy.getDefault("age", new DataType.NumericType(10, 0));
        assertThat(result).isEqualTo(new BigDecimal("18"));
    }

    @Test
    @DisplayName("falls back to type default when column not specified")
    void fallbackToTypeDefault() {
        var strategy = DefaultValueStrategy.builder()
            .columnDefault("age", new BigDecimal("18"))
            .build();

        var result = strategy.getDefault("height", new DataType.NumericType(10, 0));
        assertThat(result).isEqualTo(BigDecimal.ZERO); // Standard numeric default
    }

    @Test
    @DisplayName("multiple column defaults work correctly")
    void multipleColumnDefaults() {
        var strategy = DefaultValueStrategy.builder()
            .columnDefault("age", new BigDecimal("18"))
            .columnDefault("country", "USA")
            .columnDefault("active", Boolean.TRUE)
            .build();

        assertThat(strategy.getDefault("age", new DataType.NumericType(10, 0)))
            .isEqualTo(new BigDecimal("18"));
        assertThat(strategy.getDefault("country", new DataType.StringType()))
            .isEqualTo("USA");
        assertThat(strategy.getDefault("active", new DataType.BooleanType()))
            .isEqualTo(Boolean.TRUE);
    }

    @Test
    @DisplayName("bulk column defaults via map")
    void bulkColumnDefaults() {
        var defaults = Map.<String, Object>of(
            "age", new BigDecimal("21"),
            "country", "UK",
            "status", "pending"
        );
        var strategy = DefaultValueStrategy.builder()
            .columnDefaults(defaults)
            .build();

        assertThat(strategy.getDefault("age", new DataType.NumericType(10, 0)))
            .isEqualTo(new BigDecimal("21"));
        assertThat(strategy.getDefault("country", new DataType.StringType()))
            .isEqualTo("UK");
        assertThat(strategy.getDefault("status", new DataType.StringType()))
            .isEqualTo("pending");
    }

    @Test
    @DisplayName("disabling standard defaults returns null for unspecified columns")
    void disableStandardDefaults() {
        var strategy = DefaultValueStrategy.builder()
            .columnDefault("age", new BigDecimal("18"))
            .useStandardDefaults(false)
            .build();

        assertThat(strategy.getDefault("age", new DataType.NumericType(10, 0)))
            .isEqualTo(new BigDecimal("18"));
        assertThat(strategy.getDefault("height", new DataType.NumericType(10, 0)))
            .isNull(); // No fallback when standard defaults disabled
    }

    @Test
    @DisplayName("builder is reusable")
    void builderReusable() {
        var builder = DefaultValueStrategy.builder()
            .columnDefault("age", new BigDecimal("18"));

        var strategy1 = builder.build();
        var strategy2 = builder.columnDefault("country", "USA").build();

        // First strategy should not be affected by second build
        assertThat(strategy1.getDefault("age", new DataType.NumericType(10, 0)))
            .isEqualTo(new BigDecimal("18"));
        assertThat(strategy1.getDefault("country", new DataType.StringType()))
            .isEqualTo(""); // Falls back to standard default

        assertThat(strategy2.getDefault("country", new DataType.StringType()))
            .isEqualTo("USA");
    }

    @Test
    @DisplayName("column defaults are case-sensitive")
    void columnDefaultsCaseSensitive() {
        var strategy = DefaultValueStrategy.builder()
            .columnDefault("Age", new BigDecimal("18"))
            .build();

        assertThat(strategy.getDefault("Age", new DataType.NumericType(10, 0)))
            .isEqualTo(new BigDecimal("18"));
        assertThat(strategy.getDefault("age", new DataType.NumericType(10, 0)))
            .isEqualTo(BigDecimal.ZERO); // Different column name, falls back to type default
    }

    @Test
    @DisplayName("empty string is valid column default")
    void emptyStringDefault() {
        var strategy = DefaultValueStrategy.builder()
            .columnDefault("description", "")
            .build();

        var result = strategy.getDefault("description", new DataType.StringType());
        assertThat(result).isEqualTo("");
    }

    @Test
    @DisplayName("LocalDate as column default")
    void localDateDefault() {
        var customDate = LocalDate.of(2024, 1, 1);
        var strategy = DefaultValueStrategy.builder()
            .columnDefault("start_date", customDate)
            .build();

        var result = strategy.getDefault("start_date", new DataType.DateType());
        assertThat(result).isEqualTo(customDate);
    }

    @Test
    @DisplayName("Instant as column default")
    void instantDefault() {
        var customInstant = Instant.parse("2024-01-01T12:00:00Z");
        var strategy = DefaultValueStrategy.builder()
            .columnDefault("created_at", customInstant)
            .build();

        var result = strategy.getDefault("created_at", new DataType.DateTimeType());
        assertThat(result).isEqualTo(customInstant);
    }

    @Test
    @DisplayName("combining column defaults with type fallbacks")
    void combiningColumnAndTypeDefaults() {
        var strategy = DefaultValueStrategy.builder()
            .columnDefault("special_age", new BigDecimal("99"))
            .useStandardDefaults(true)
            .build();

        // Column-specific default
        assertThat(strategy.getDefault("special_age", new DataType.NumericType(10, 0)))
            .isEqualTo(new BigDecimal("99"));

        // Type fallback for other numeric columns
        assertThat(strategy.getDefault("height", new DataType.NumericType(10, 0)))
            .isEqualTo(BigDecimal.ZERO);

        // Type fallback for string columns
        assertThat(strategy.getDefault("name", new DataType.StringType()))
            .isEqualTo("");

        // Type fallback for boolean columns
        assertThat(strategy.getDefault("active", new DataType.BooleanType()))
            .isEqualTo(Boolean.FALSE);
    }
}
