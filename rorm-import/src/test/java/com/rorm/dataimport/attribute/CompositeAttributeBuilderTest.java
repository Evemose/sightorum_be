package com.rorm.dataimport.attribute;

import com.rorm.dataimport.naming.NamingStyle;
import com.rorm.dataimport.override.SchemaOverride;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CompositeAttributeBuilder sub-attribute detection")
class CompositeAttributeBuilderTest {

    private final CompositeAttributeBuilder builder =
        new CompositeAttributeBuilder(NamingStyle.SNAKE_CASE, ",", "people");

    @Test
    @DisplayName("strips the shared column prefix from sub-attribute names")
    void stripsCommonPrefix() {
        var result = builder.buildSubAttributes(
            List.of("customer_first_name", "customer_last_name"), null, new HashSet<>());

        assertThat(result).containsOnlyKeys("firstName", "lastName");
        assertThat(((DetectedAttribute.Basic) result.get("firstName")).source().sourceColumn())
            .isEqualTo("customer_first_name");
    }

    @Test
    @DisplayName("keeps column names verbatim when there is no shared prefix")
    void keepsNamesWithoutCommonPrefix() {
        var result = builder.buildSubAttributes(List.of("zip", "city"), null, new HashSet<>());

        assertThat(result).containsOnlyKeys("zip", "city");
        assertThat(result.get("zip")).isInstanceOf(DetectedAttribute.Basic.class);
    }

    @Test
    @DisplayName("honors a nested override's attribute kind instead of defaulting to basic")
    void appliesNestedOverrideKind() {
        var overrides = List.<SchemaOverride>of(
            new SchemaOverride.SingularReferenceOverride("companyId", "companies"));

        var result = builder.buildSubAttributes(
            List.of("customer_company_id", "customer_city"), overrides, new HashSet<>());

        assertThat(result.get("companyId")).isInstanceOf(DetectedAttribute.SingularReference.class);
        assertThat(result.get("city")).isInstanceOf(DetectedAttribute.Basic.class);
    }

    @Test
    @DisplayName("rejects a one-to-one root override nested inside a composite")
    void rejectsNestedOneToOneRoot() {
        var overrides = List.<SchemaOverride>of(
            new SchemaOverride.OneToOneRootOverride("addr", "addresses", List.of("addr_zip"), List.of(), null));

        assertThatThrownBy(() -> builder.buildSubAttributes(List.of("addr_zip"), overrides, new HashSet<>()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cannot be nested");
    }

    @Test
    @DisplayName("builds a nested composite from a composite override")
    void buildsNestedComposite() {
        var overrides = List.<SchemaOverride>of(
            new SchemaOverride.CompositeAttributeOverride("geo", List.of("geo_lat", "geo_lon"), List.of()));

        var result = builder.buildSubAttributes(List.of("geo_lat", "geo_lon"), overrides, new HashSet<>());

        assertThat(result.get("geo")).isInstanceOfSatisfying(DetectedAttribute.Composite.class,
            composite -> assertThat(composite.subAttributes()).containsOnlyKeys("lat", "lon"));
    }
}
