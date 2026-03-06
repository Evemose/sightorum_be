package com.rorm.dataimport.naming;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("NamingStyle")
class NamingStyleTest {

    @Test
    @DisplayName("CAMEL_CASE splits by case change")
    void camelCaseSplits() {
        var parts = NamingStyle.CAMEL_CASE.split("firstName");

        assertThat(parts).containsExactly("first", "Name");
    }

    @Test
    @DisplayName("SNAKE_CASE splits by underscore")
    void snakeCaseSplits() {
        var parts = NamingStyle.SNAKE_CASE.split("first_name");

        assertThat(parts).containsExactly("first", "name");
    }

    @Test
    @DisplayName("KEBAB_CASE splits by dash")
    void kebabCaseSplits() {
        var parts = NamingStyle.KEBAB_CASE.split("first-name");

        assertThat(parts).containsExactly("first", "name");
    }

    @Test
    @DisplayName("converts parts to camelCase")
    void convertsPartsToCamelCase() {
        var result = NamingStyle.CAMEL_CASE.join("first", "name");

        assertThat(result).isEqualTo("firstName");
    }

    @Test
    @DisplayName("converts single part to camelCase")
    void convertsSinglePartToCamelCase() {
        var result = NamingStyle.CAMEL_CASE.join("name");

        assertThat(result).isEqualTo("name");
    }
}
