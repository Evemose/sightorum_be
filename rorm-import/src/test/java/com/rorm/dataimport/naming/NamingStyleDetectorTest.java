package com.rorm.dataimport.naming;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("NamingStyleDetector")
class NamingStyleDetectorTest {

    private final NamingStyleDetector detector = new NamingStyleDetector();

    @Test
    @DisplayName("detects camelCase naming style")
    void detectsCamelCase() {
        var properties = List.of("firstName", "lastName", "emailAddress");

        var style = detector.detect(properties);

        assertThat(style).isEqualTo(NamingStyle.CAMEL_CASE);
    }

    @Test
    @DisplayName("detects PascalCase naming style")
    void detectsPascalCase() {
        var properties = List.of("FirstName", "LastName", "EmailAddress");

        var style = detector.detect(properties);

        assertThat(style).isEqualTo(NamingStyle.PASCAL_CASE);
    }

    @Test
    @DisplayName("detects snake_case naming style")
    void detectsSnakeCase() {
        var properties = List.of("first_name", "last_name", "email_address");

        var style = detector.detect(properties);

        assertThat(style).isEqualTo(NamingStyle.SNAKE_CASE);
    }

    @Test
    @DisplayName("detects kebab-case naming style")
    void detectsKebabCase() {
        var properties = List.of("first-name", "last-name", "email-address");

        var style = detector.detect(properties);

        assertThat(style).isEqualTo(NamingStyle.KEBAB_CASE);
    }

    @Test
    @DisplayName("throws exception when property list is empty")
    void throwsExceptionWhenEmpty() {
        assertThatThrownBy(() -> detector.detect(List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Cannot detect naming style from empty property list");
    }

    @Test
    @DisplayName("detects style with single property")
    void detectsStyleWithSingleProperty() {
        var properties = List.of("first_name");

        var style = detector.detect(properties);

        assertThat(style).isEqualTo(NamingStyle.SNAKE_CASE);
    }
}
