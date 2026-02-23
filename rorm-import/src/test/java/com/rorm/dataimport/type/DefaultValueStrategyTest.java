package com.rorm.dataimport.type;

import com.rorm.metamodel.DataType;
import com.rorm.metamodel.DataType.CategorcialType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("UseDefault Coercion Defaults")
class DefaultValueStrategyTest {

    @Test
    @DisplayName("returns numeric zero when default value is null")
    void returnsNumericZeroWhenDefaultNull() {
        var strategy = new InMemoryCoercion.UseDefault(null);
        var result = strategy.coerce("invalid", new DataType.NumericType(10, 0), "age");
        assertThat(result).isEqualTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("returns empty string when default value is null for string type")
    void returnsEmptyStringWhenDefaultNullForString() {
        var strategy = new InMemoryCoercion.UseDefault(null);
        var result = strategy.coerce("invalid", new DataType.StringType(), "name");
        assertThat(result).isEqualTo("");
    }

    @Test
    @DisplayName("returns boolean false when default value is null for boolean type")
    void returnsFalseWhenDefaultNullForBoolean() {
        var strategy = new InMemoryCoercion.UseDefault(null);
        var result = strategy.coerce("invalid", new DataType.BooleanType(), "active");
        assertThat(result).isEqualTo(Boolean.FALSE);
    }

    @Test
    @DisplayName("returns temporal defaults when default value is null")
    void returnsTemporalDefaultsWhenDefaultNull() {
        var strategy = new InMemoryCoercion.UseDefault(null);

        assertThat(strategy.coerce("invalid", new DataType.DateType(), "d")).isEqualTo(LocalDate.EPOCH);
        assertThat(strategy.coerce("invalid", new DataType.TimeType(), "t")).isEqualTo(LocalTime.MIDNIGHT);
        assertThat(strategy.coerce("invalid", new DataType.DateTimeType(), "dt")).isEqualTo(Instant.EPOCH);
        assertThat(strategy.coerce("invalid", new DataType.TimezoneType(), "tz")).isEqualTo(ZoneOffset.UTC);
        assertThat(strategy.coerce("invalid", new DataType.DayOfWeekType(), "dow")).isEqualTo(DayOfWeek.MONDAY);
    }

    @Test
    @DisplayName("returns first enum value when default value is null")
    void returnsFirstEnumValueWhenDefaultNull() {
        var strategy = new InMemoryCoercion.UseDefault(null);
        var enumType = new CategorcialType(new String[]{"red", "green", "blue"});
        var result = strategy.coerce("invalid", enumType, "color");
        assertThat(result).isEqualTo("red");
    }

    @Test
    @DisplayName("returns explicit default value regardless of target type")
    void returnsExplicitDefaultRegardlessOfType() {
        var strategy = new InMemoryCoercion.UseDefault("fallback");

        assertThat(strategy.coerce("bad", new DataType.StringType(), "name")).isEqualTo("fallback");
        assertThat(strategy.coerce("bad", new DataType.NumericType(10, 2), "amount")).isEqualTo("fallback");
        assertThat(strategy.coerce("bad", new DataType.BooleanType(), "active")).isEqualTo("fallback");
    }
}
