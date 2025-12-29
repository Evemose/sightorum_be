package com.rorm.dataimport.attribute;

import com.rorm.dataimport.naming.NamingStyle;
import com.rorm.dataimport.override.SchemaOverride;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AttributeTypeDetector")
class AttributeTypeDetectorTest {

    private static final NamingStyle SNAKE_CASE = NamingStyle.SNAKE_CASE;
    private static final Set<String> AVAILABLE_ROOTS = Set.of("user", "order", "product");

    @Test
    @DisplayName("detects basic attributes from simple columns")
    void detectBasicAttributes() {
        var detector = new AttributeTypeDetector(Set.of(), SNAKE_CASE, List.of(), ";");
        var columns = List.of("first_name", "last_name", "email");

        var result = detector.detectAttributes(columns);

        assertThat(result).hasSize(3);
        assertThat(result.get("firstName"))
            .isInstanceOf(DetectedAttribute.Basic.class)
            .extracting(a -> (DetectedAttribute.Basic) a)
            .satisfies(attr -> {
                assertThat(attr.name()).isEqualTo("firstName");
                assertThat(attr.columnName()).isEqualTo("first_name");
                assertThat(attr.type()).isEqualTo("string");
            });
    }

    @Test
    @DisplayName("detects singular reference when column ends with _id and matches available root")
    void detectSingularReference() {
        var detector = new AttributeTypeDetector(AVAILABLE_ROOTS, SNAKE_CASE, List.of(), ";");
        var columns = List.of("user_id", "order_id", "name");

        var result = detector.detectAttributes(columns);

        assertThat(result).hasSize(3);
        assertThat(result.get("userId"))
            .isInstanceOf(DetectedAttribute.SingularReference.class)
            .extracting(a -> (DetectedAttribute.SingularReference) a)
            .satisfies(attr -> {
                assertThat(attr.name()).isEqualTo("userId");
                assertThat(attr.columnName()).isEqualTo("user_id");
                assertThat(attr.targetRootName()).isEqualTo("user");
            });
        assertThat(result.get("orderId"))
            .isInstanceOf(DetectedAttribute.SingularReference.class);
    }

    @Test
    @DisplayName("detects plural reference when column ends with _ids and matches available root")
    void detectPluralReference() {
        var detector = new AttributeTypeDetector(AVAILABLE_ROOTS, SNAKE_CASE, List.of(), ";");
        var columns = List.of("user_ids", "product_ids", "name");

        var result = detector.detectAttributes(columns);

        assertThat(result).hasSize(3);
        assertThat(result.get("userIds"))
            .isInstanceOf(DetectedAttribute.PluralReference.class)
            .extracting(a -> (DetectedAttribute.PluralReference) a)
            .satisfies(attr -> {
                assertThat(attr.name()).isEqualTo("userIds");
                assertThat(attr.columnName()).isEqualTo("user_ids");
                assertThat(attr.targetRootName()).isEqualTo("user");
            });
    }

    @Test
    @DisplayName("detects composite attribute from columns with shared prefix")
    void detectCompositeAttribute() {
        var detector = new AttributeTypeDetector(Set.of(), SNAKE_CASE, List.of(), ";");
        var columns = List.of("address_street", "address_city", "address_zip", "name");

        var result = detector.detectAttributes(columns);

        assertThat(result).hasSize(2);
        assertThat(result.get("name")).isInstanceOf(DetectedAttribute.Basic.class);
        assertThat(result.get("address"))
            .isInstanceOf(DetectedAttribute.Composite.class)
            .extracting(a -> (DetectedAttribute.Composite) a)
            .satisfies(composite -> {
                assertThat(composite.name()).isEqualTo("address");
                assertThat(composite.subAttributes()).hasSize(3);
                assertThat(composite.subAttributes().get("street"))
                    .isInstanceOf(DetectedAttribute.Basic.class)
                    .extracting(a -> (DetectedAttribute.Basic) a)
                    .satisfies(attr -> {
                        assertThat(attr.columnName()).isEqualTo("address_street");
                        assertThat(attr.type()).isEqualTo("string");
                    });
            });
    }

    @Test
    @DisplayName("detects one-to-one root when columns have prefix_id pattern and prefix is not an available root")
    void detectOneToOneRoot() {
        var detector = new AttributeTypeDetector(Set.of(), SNAKE_CASE, List.of(), ";");
        var columns = List.of("profile_id", "profile_name", "profile_email", "username");

        var result = detector.detectAttributes(columns);

        assertThat(result).hasSize(2);
        assertThat(result.get("username")).isInstanceOf(DetectedAttribute.Basic.class);
        assertThat(result.get("profile"))
            .isInstanceOf(DetectedAttribute.OneToOneRoot.class)
            .extracting(a -> (DetectedAttribute.OneToOneRoot) a)
            .satisfies(oneToOne -> {
                assertThat(oneToOne.name()).isEqualTo("profile");
                assertThat(oneToOne.targetRootName()).isEqualTo("profile");
                assertThat(oneToOne.subAttributes()).hasSize(3);
                assertThat(oneToOne.subAttributes()).containsKeys("id", "name", "email");
            });
    }

    @Test
    @DisplayName("detects composite when prefix_id exists but prefix IS an available root")
    void detectCompositeWhenPrefixIsAvailableRoot() {
        var detector = new AttributeTypeDetector(Set.of("user"), SNAKE_CASE, List.of(), ";");
        var columns = List.of("user_id", "user_name", "user_email");

        var result = detector.detectAttributes(columns);

        // Since "user" is an available root, user_id should be detected as a reference
        // and user_name, user_email should form a composite (if there's more than one non-id column)
        // Actually, user_id is a reference, the others would be composite
        assertThat(result).hasSize(2);
        assertThat(result.get("userId")).isInstanceOf(DetectedAttribute.SingularReference.class);
        assertThat(result.get("user")).isInstanceOf(DetectedAttribute.Composite.class);
    }

    @Test
    @DisplayName("basic override takes precedence over detection")
    void basicOverrideTakesPrecedence() {
        List<SchemaOverride> overrides = List.of(
            new SchemaOverride.BasicAttributeOverride("firstName", "integer")
        );
        var detector = new AttributeTypeDetector(Set.of(), SNAKE_CASE, overrides, ";");
        var columns = List.of("first_name", "last_name");

        var result = detector.detectAttributes(columns);

        assertThat(result).hasSize(2);
        assertThat(result.get("firstName"))
            .isInstanceOf(DetectedAttribute.Basic.class)
            .extracting(a -> (DetectedAttribute.Basic) a)
            .satisfies(attr -> assertThat(attr.type()).isEqualTo("integer"));
        assertThat(result.get("lastName"))
            .isInstanceOf(DetectedAttribute.Basic.class)
            .extracting(a -> (DetectedAttribute.Basic) a)
            .satisfies(attr -> assertThat(attr.type()).isEqualTo("string"));
    }

    @Test
    @DisplayName("composite override with explicit sub-attribute columns")
    void compositeOverrideWithSubAttributeColumns() {
        List<SchemaOverride> overrides = List.of(
            new SchemaOverride.CompositeAttributeOverride(
                "address",
                List.of("street_address", "city_name", "postal_code"),
                null
            )
        );
        var detector = new AttributeTypeDetector(Set.of(), SNAKE_CASE, overrides, ";");
        var columns = List.of("street_address", "city_name", "postal_code", "country");

        var result = detector.detectAttributes(columns);

        assertThat(result).hasSize(2);
        assertThat(result.get("country")).isInstanceOf(DetectedAttribute.Basic.class);
        assertThat(result.get("address"))
            .isInstanceOf(DetectedAttribute.Composite.class)
            .extracting(a -> (DetectedAttribute.Composite) a)
            .satisfies(composite -> {
                assertThat(composite.subAttributes()).hasSize(3);
                assertThat(composite.subAttributes()).containsKeys("streetAddress", "cityName", "postalCode");
            });
    }

    @Test
    @DisplayName("composite override with nested overrides for sub-attributes")
    void compositeOverrideWithNestedOverrides() {
        var result = detectAttributeTypesForNestedOverridesCase();

        assertThat(result).hasSize(2);
        assertThat(result.get("name")).isInstanceOf(DetectedAttribute.Basic.class);
        assertThat(result.get("address"))
            .isInstanceOf(DetectedAttribute.Composite.class)
            .extracting(a -> (DetectedAttribute.Composite) a)
            .satisfies(composite -> {
                assertThat(composite.subAttributes()).hasSize(3);
                // Nested overrides should apply
                assertThat(composite.subAttributes().get("street"))
                    .isInstanceOf(DetectedAttribute.Basic.class)
                    .extracting(a -> (DetectedAttribute.Basic) a)
                    .satisfies(attr -> assertThat(attr.type()).isEqualTo("text"));
                assertThat(composite.subAttributes().get("zipCode"))
                    .isInstanceOf(DetectedAttribute.Basic.class)
                    .extracting(a -> (DetectedAttribute.Basic) a)
                    .satisfies(attr -> assertThat(attr.type()).isEqualTo("integer"));
                // City should default to string
                assertThat(composite.subAttributes().get("city"))
                    .isInstanceOf(DetectedAttribute.Basic.class)
                    .extracting(a -> (DetectedAttribute.Basic) a)
                    .satisfies(attr -> assertThat(attr.type()).isEqualTo("string"));
            });
    }

    private static Map<String, DetectedAttribute> detectAttributeTypesForNestedOverridesCase() {
        List<SchemaOverride> nestedOverrides = List.of(
            new SchemaOverride.BasicAttributeOverride("street", "text"),
            new SchemaOverride.BasicAttributeOverride("zipCode", "integer")
        );
        List<SchemaOverride> overrides = List.of(
            new SchemaOverride.CompositeAttributeOverride(
                "address",
                List.of("address_street", "address_city", "address_zip_code"),
                nestedOverrides
            )
        );
        var detector = new AttributeTypeDetector(Set.of(), SNAKE_CASE, overrides, ";");
        var columns = List.of("address_street", "address_city", "address_zip_code", "name");

        return detector.detectAttributes(columns);
    }

    @Test
    @DisplayName("one-to-one root override creates OneToOneRoot attribute")
    void oneToOneRootOverride() {
        List<SchemaOverride> nestedOverrides = List.of(
            new SchemaOverride.BasicAttributeOverride("bio", "text")
        );
        List<SchemaOverride> overrides = List.of(
            new SchemaOverride.OneToOneRootOverride(
                "profile",
                "user_profile",
                List.of("profile_id", "profile_bio", "profile_avatar"),
                nestedOverrides
            )
        );
        var detector = new AttributeTypeDetector(Set.of(), SNAKE_CASE, overrides, ";");
        var columns = List.of("profile_id", "profile_bio", "profile_avatar", "username");

        var result = detector.detectAttributes(columns);

        assertThat(result).hasSize(2);
        assertThat(result.get("username")).isInstanceOf(DetectedAttribute.Basic.class);
        assertThat(result.get("profile"))
            .isInstanceOf(DetectedAttribute.OneToOneRoot.class)
            .extracting(a -> (DetectedAttribute.OneToOneRoot) a)
            .satisfies(oneToOne -> {
                assertThat(oneToOne.name()).isEqualTo("profile");
                assertThat(oneToOne.targetRootName()).isEqualTo("user_profile");
                assertThat(oneToOne.subAttributes()).hasSize(3);
                assertThat(oneToOne.subAttributes().get("bio"))
                    .isInstanceOf(DetectedAttribute.Basic.class)
                    .extracting(a -> (DetectedAttribute.Basic) a)
                    .satisfies(attr -> assertThat(attr.type()).isEqualTo("text"));
            });
    }

    @Test
    @DisplayName("collection override with custom separator")
    void collectionOverride() {
        List<SchemaOverride> overrides = List.of(
            new SchemaOverride.CollectionAttributeOverride("tags", ",")
        );
        var detector = new AttributeTypeDetector(Set.of(), SNAKE_CASE, overrides, ";");
        var columns = List.of("tags", "name");

        var result = detector.detectAttributes(columns);

        assertThat(result).hasSize(2);
        assertThat(result.get("name")).isInstanceOf(DetectedAttribute.Basic.class);
        assertThat(result.get("tags"))
            .isInstanceOf(DetectedAttribute.Collection.class)
            .extracting(a -> (DetectedAttribute.Collection) a)
            .satisfies(collection -> {
                assertThat(collection.columnName()).isEqualTo("tags");
                assertThat(collection.separator()).isEqualTo(",");
            });
    }

    @Test
    @DisplayName("reference override takes precedence even when root doesn't exist")
    void referenceOverrideTakesPrecedence() {
        List<SchemaOverride> overrides = List.of(
            new SchemaOverride.SingularReferenceOverride("customerId", "customer")
        );
        var detector = new AttributeTypeDetector(Set.of(), SNAKE_CASE, overrides, ";");
        var columns = List.of("customer_id", "order_id");

        var result = detector.detectAttributes(columns);

        assertThat(result).hasSize(2);
        assertThat(result.get("customerId"))
            .isInstanceOf(DetectedAttribute.SingularReference.class)
            .extracting(a -> (DetectedAttribute.SingularReference) a)
            .satisfies(attr -> assertThat(attr.targetRootName()).isEqualTo("customer"));
        // order_id should be basic since "order" is not in available roots
        assertThat(result.get("orderId")).isInstanceOf(DetectedAttribute.Basic.class);
    }

    @Test
    @DisplayName("override prevents composite detection for same attribute name")
    void compositeNotDetectedWhenOverrideExists() {
        List<SchemaOverride> overrides = List.of(
            new SchemaOverride.BasicAttributeOverride("address", "text")
        );
        var detector = new AttributeTypeDetector(Set.of(), SNAKE_CASE, overrides, ";");
        var columns = List.of("address", "address_street", "address_city");

        var result = detector.detectAttributes(columns);

        // The override claims "address" column, so address_street and address_city
        // should not form a composite and remain as separate basic attributes
        assertThat(result).hasSize(3);
        assertThat(result.get("address"))
            .isInstanceOf(DetectedAttribute.Basic.class)
            .extracting(a -> (DetectedAttribute.Basic) a)
            .satisfies(attr -> assertThat(attr.type()).isEqualTo("text"));
        assertThat(result.get("addressStreet")).isInstanceOf(DetectedAttribute.Basic.class);
        assertThat(result.get("addressCity")).isInstanceOf(DetectedAttribute.Basic.class);
    }
}
