package com.rorm.dataimport.type;

import com.rorm.metamodel.DataType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("InvalidValueCoercionStrategy")
class InvalidValueCoercionStrategyTest {

    // ==================== Skip Strategy Tests ====================

    @Test
    @DisplayName("Skip.INSTANCE should return null for any value")
    void skipShouldReturnNull() {
        var strategy = InMemoryCoercion.Skip.INSTANCE;
        var dataType = new DataType.StringType();

        assertThat(strategy.coerce("valid", dataType, "col")).isNull();
        assertThat(strategy.coerce(null, dataType, "col")).isNull();
        assertThat(strategy.coerce("", dataType, "col")).isNull();
    }

    @Test
    @DisplayName("Skip.processForDetection should trim and skip empty")
    void skipProcessForDetectionShouldTrimAndSkipEmpty() {
        var strategy = InMemoryCoercion.Skip.INSTANCE;

        assertThat(strategy.processForDetection("  hello  ")).isEqualTo("hello");
        assertThat(strategy.processForDetection("value")).isEqualTo("value");
        assertThat(strategy.processForDetection("")).isNull();
        assertThat(strategy.processForDetection(null)).isNull();
    }

    // ==================== UseDefault Strategy Tests ====================

    @Test
    @DisplayName("UseDefault with standard defaults should return type-appropriate defaults")
    void useDefaultWithStandardDefaultsShouldReturnDefaults() {
        var strategy = InMemoryCoercion.UseDefault.withStandardDefaults();

        var numericType = new DataType.NumericType(10, 2);
        assertThat(strategy.coerce(null, numericType, "amount"))
            .isEqualTo(java.math.BigDecimal.ZERO);

        var stringType = new DataType.StringType();
        assertThat(strategy.coerce(null, stringType, "name"))
            .isEqualTo("");

        var booleanType = new DataType.BooleanType();
        assertThat(strategy.coerce(null, booleanType, "active"))
            .isEqualTo(Boolean.FALSE);
    }

    @Test
    @DisplayName("UseDefault with no defaults should return null")
    void useDefaultWithNoDefaultsShouldReturnNull() {
        var strategy = InMemoryCoercion.UseDefault.withNoDefaults();

        var numericType = new DataType.NumericType(10, 2);
        assertThat(strategy.coerce(null, numericType, "amount")).isNull();

        var stringType = new DataType.StringType();
        assertThat(strategy.coerce(null, stringType, "name")).isNull();
    }

    @Test
    @DisplayName("UseDefault with column-specific defaults should use them")
    void useDefaultWithColumnDefaultsShouldUseThem() {
        var defaultStrategy = DefaultValueStrategy.builder()
            .columnDefault("amount", java.math.BigDecimal.valueOf(100))
            .columnDefault("name", "Unknown")
            .build();

        var strategy = new InMemoryCoercion.UseDefault(defaultStrategy);

        var numericType = new DataType.NumericType(10, 2);
        assertThat(strategy.coerce(null, numericType, "amount"))
            .isEqualTo(java.math.BigDecimal.valueOf(100));

        var stringType = new DataType.StringType();
        assertThat(strategy.coerce(null, stringType, "name"))
            .isEqualTo("Unknown");
    }

    // ==================== ThrowOnInvalid Strategy Tests ====================

    @Test
    @DisplayName("ThrowOnInvalid should throw IllegalArgumentException")
    void throwOnInvalidShouldThrow() {
        var strategy = InMemoryCoercion.ThrowOnInvalid.INSTANCE;
        var dataType = new DataType.NumericType(10, 2);

        assertThatThrownBy(() -> strategy.coerce("invalid", dataType, "amount"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid value")
            .hasMessageContaining("amount")
            .hasMessageContaining("NumericType");
    }

    @Test
    @DisplayName("ThrowOnInvalid should throw for null values")
    void throwOnInvalidShouldThrowForNull() {
        var strategy = InMemoryCoercion.ThrowOnInvalid.INSTANCE;
        var dataType = new DataType.StringType();

        assertThatThrownBy(() -> strategy.coerce(null, dataType, "name"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ==================== processForDetection Tests ====================

    @Test
    @DisplayName("processForDetection default implementation should trim whitespace")
    void processForDetectionShouldTrimWhitespace() {
        var strategy = InMemoryCoercion.Skip.INSTANCE;

        assertThat(strategy.processForDetection("  value  ")).isEqualTo("value");
        assertThat(strategy.processForDetection("\tvalue\n")).isEqualTo("value");
        assertThat(strategy.processForDetection("no-spaces")).isEqualTo("no-spaces");
    }

    @Test
    @DisplayName("processForDetection default implementation should return null for empty")
    void processForDetectionShouldReturnNullForEmpty() {
        var strategy = InMemoryCoercion.Skip.INSTANCE;

        assertThat(strategy.processForDetection("")).isNull();
        assertThat(strategy.processForDetection("   ")).isNull();
        assertThat(strategy.processForDetection("\t\n")).isNull();
        assertThat(strategy.processForDetection(null)).isNull();
    }

    // processForDetection override test removed - sealed interfaces don't support anonymous classes
}
