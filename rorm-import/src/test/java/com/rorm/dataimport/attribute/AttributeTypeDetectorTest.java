package com.rorm.dataimport.attribute;

import com.rorm.dataimport.naming.NamingStyle;
import com.rorm.dataimport.override.SchemaOverride;
import com.rorm.metamodel.DataType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AttributeTypeDetector")
class AttributeTypeDetectorTest {

    private static final NamingStyle SNAKE_CASE = NamingStyle.SNAKE_CASE;
    private static final Set<String> AVAILABLE_ROOTS = Set.of("user", "order", "product");
    private static final String DATA_SOURCE = "test_source";
    private static final String CURRENT_ROOT = "current_root";

    @Test
    @DisplayName("detects basic attributes from simple columns")
    void detectBasicAttributes() {
        var detector = new AttributeTypeDetector(Set.of(), SNAKE_CASE, List.of(), ";", DATA_SOURCE, CURRENT_ROOT);
        var columns = List.of("first_name", "last_name", "email");

        var result = detector.detectAttributes(columns);

        assertThat(result).hasSize(3);
        assertThat(result.get("firstName"))
            .isInstanceOf(DetectedAttribute.Basic.class)
            .extracting(a -> (DetectedAttribute.Basic) a)
            .satisfies(attr -> {
                assertThat(attr.name()).isEqualTo("firstName");
                assertThat(attr.source().sourceColumn()).isEqualTo("first_name");
            });
    }

    @Test
    @DisplayName("detects singular reference when column ends with _id and matches available root")
    void detectSingularReference() {
        var detector = new AttributeTypeDetector(AVAILABLE_ROOTS, SNAKE_CASE, List.of(), ";", DATA_SOURCE, CURRENT_ROOT);
        var columns = List.of("user_id", "order_id", "name");

        var result = detector.detectAttributes(columns);

        assertThat(result).hasSize(3);
        assertThat(result.get("userId"))
            .isInstanceOf(DetectedAttribute.SingularReference.class)
            .extracting(a -> (DetectedAttribute.SingularReference) a)
            .satisfies(attr -> {
                assertThat(attr.name()).isEqualTo("userId");
                assertThat(attr.source().sourceColumn()).isEqualTo("user_id");
                assertThat(attr.targetRootName()).isEqualTo("user");
            });
        assertThat(result.get("orderId"))
            .isInstanceOf(DetectedAttribute.SingularReference.class);
    }

    @Test
    @DisplayName("detects plural reference when column ends with _ids and matches available root")
    void detectPluralReference() {
        var detector = new AttributeTypeDetector(AVAILABLE_ROOTS, SNAKE_CASE, List.of(), ";", DATA_SOURCE, CURRENT_ROOT);
        var columns = List.of("user_ids", "product_ids", "name");

        var result = detector.detectAttributes(columns);

        assertThat(result).hasSize(3);
        assertThat(result.get("userIds"))
            .isInstanceOf(DetectedAttribute.PluralReference.class)
            .extracting(a -> (DetectedAttribute.PluralReference) a)
            .satisfies(attr -> {
                assertThat(attr.name()).isEqualTo("userIds");
                assertThat(attr.source().sourceColumn()).isEqualTo("user_ids");
                assertThat(attr.targetRootName()).isEqualTo("user");
            });
    }

    @Test
    @DisplayName("detects composite attribute from columns with shared prefix")
    void detectCompositeAttribute() {
        var detector = new AttributeTypeDetector(Set.of(), SNAKE_CASE, List.of(), ";", DATA_SOURCE, CURRENT_ROOT);
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
                    .satisfies(attr -> assertThat(attr.source().sourceColumn()).isEqualTo("address_street"));
            });
    }

    @Test
    @DisplayName("treats columns as basic attributes by default (collections need explicit override)")
    void detectBasicAttributesByDefault() {
        var detector = new AttributeTypeDetector(Set.of(), SNAKE_CASE, List.of(), ";", DATA_SOURCE, CURRENT_ROOT);
        var columns = List.of("tags", "categories", "name");

        var result = detector.detectAttributes(columns);

        assertThat(result).hasSize(3);
        assertThat(result.get("tags"))
            .isInstanceOf(DetectedAttribute.Basic.class)
            .extracting(a -> (DetectedAttribute.Basic) a)
            .satisfies(attr -> {
                assertThat(attr.name()).isEqualTo("tags");
                assertThat(attr.source().sourceColumn()).isEqualTo("tags");
            });
    }

    @Test
    @DisplayName("applies basic attribute override")
    void applyBasicAttributeOverride() {
        List<SchemaOverride> overrides = List.of(
            new SchemaOverride.BasicAttributeOverride("firstName", new DataType.NumericType(10, 0))
        );
        var detector = new AttributeTypeDetector(Set.of(), SNAKE_CASE, overrides, ";", DATA_SOURCE, CURRENT_ROOT);
        var columns = List.of("first_name", "last_name");

        var result = detector.detectAttributes(columns);

        assertThat(result).hasSize(2);
        assertThat(result.get("firstName"))
            .isInstanceOf(DetectedAttribute.Basic.class)
            .extracting(a -> (DetectedAttribute.Basic) a)
            .satisfies(attr -> {
                assertThat(attr.name()).isEqualTo("firstName");
                assertThat(attr.source().sourceColumn()).isEqualTo("first_name");
            });
        assertThat(result.get("lastName"))
            .isInstanceOf(DetectedAttribute.Basic.class);
    }

    @Test
    @DisplayName("applies composite override with explicit sub-attribute columns")
    void applyCompositeOverride() {
        var nestedOverrides = List.<SchemaOverride>of(
            new SchemaOverride.BasicAttributeOverride("street", new DataType.StringType()),
            new SchemaOverride.BasicAttributeOverride("zipCode", new DataType.NumericType(10, 0))
        );

        List<SchemaOverride> overrides = List.of(
            new SchemaOverride.CompositeAttributeOverride(
                "address",
                List.of("address_street", "address_city", "address_zip_code"),
                nestedOverrides
            )
        );

        var detector = new AttributeTypeDetector(Set.of(), SNAKE_CASE, overrides, ";", DATA_SOURCE, CURRENT_ROOT);
        var columns = List.of("name", "address_street", "address_city", "address_zip_code");

        var result = detector.detectAttributes(columns);

        assertThat(result).hasSize(2);
        assertThat(result.get("address"))
            .isInstanceOf(DetectedAttribute.Composite.class)
            .extracting(a -> (DetectedAttribute.Composite) a)
            .satisfies(composite -> {
                assertThat(composite.subAttributes()).hasSize(3);
                assertThat(composite.subAttributes().get("street"))
                    .isInstanceOf(DetectedAttribute.Basic.class)
                    .extracting(a -> (DetectedAttribute.Basic) a)
                    .satisfies(attr -> assertThat(attr.source().sourceColumn()).isEqualTo("address_street"));
                assertThat(composite.subAttributes().get("zipCode"))
                    .isInstanceOf(DetectedAttribute.Basic.class)
                    .extracting(a -> (DetectedAttribute.Basic) a)
                    .satisfies(attr -> assertThat(attr.source().sourceColumn()).isEqualTo("address_zip_code"));
                assertThat(composite.subAttributes().get("city"))
                    .isInstanceOf(DetectedAttribute.Basic.class)
                    .extracting(a -> (DetectedAttribute.Basic) a)
                    .satisfies(attr -> assertThat(attr.source().sourceColumn()).isEqualTo("address_city"));
            });
    }

    @Test
    @DisplayName("applies singular reference override")
    void applySingularReferenceOverride() {
        List<SchemaOverride> overrides = List.of(
            new SchemaOverride.SingularReferenceOverride("authorName", "users")
        );
        var detector = new AttributeTypeDetector(Set.of(), SNAKE_CASE, overrides, ";", DATA_SOURCE, CURRENT_ROOT);
        var columns = List.of("title", "author_name");

        var result = detector.detectAttributes(columns);

        assertThat(result).hasSize(2);
        assertThat(result.get("authorName"))
            .isInstanceOf(DetectedAttribute.SingularReference.class)
            .extracting(a -> (DetectedAttribute.SingularReference) a)
            .satisfies(attr -> {
                assertThat(attr.name()).isEqualTo("authorName");
                assertThat(attr.source().sourceColumn()).isEqualTo("author_name");
                assertThat(attr.targetRootName()).isEqualTo("users");
            });
    }

    @Test
    @DisplayName("applies collection attribute override with custom separator")
    void applyCollectionAttributeOverride() {
        List<SchemaOverride> overrides = List.of(
            new SchemaOverride.CollectionAttributeOverride("tags", null, ",")
        );
        var detector = new AttributeTypeDetector(Set.of(), SNAKE_CASE, overrides, ";", DATA_SOURCE, CURRENT_ROOT);
        var columns = List.of("name", "tags");

        var result = detector.detectAttributes(columns);

        assertThat(result).hasSize(2);
        assertThat(result.get("tags"))
            .isInstanceOf(DetectedAttribute.Collection.class)
            .extracting(a -> (DetectedAttribute.Collection) a)
            .satisfies(attr -> {
                assertThat(attr.name()).isEqualTo("tags");
                assertThat(attr.source().sourceColumn()).isEqualTo("tags");
                assertThat(attr.separator()).isEqualTo(",");
            });
    }

    @Test
    @DisplayName("applies one-to-one root override")
    void applyOneToOneRootOverride() {
        var nestedOverrides = List.<SchemaOverride>of(
            new SchemaOverride.BasicAttributeOverride("bio", new DataType.StringType())
        );

        List<SchemaOverride> overrides = List.of(
            new SchemaOverride.OneToOneRootOverride(
                "profile",
                "user_profiles",
                List.of("profile_id", "profile_bio"),
                nestedOverrides,
                null  // No explicit ID column specified
            )
        );

        var detector = new AttributeTypeDetector(Set.of(), SNAKE_CASE, overrides, ";", DATA_SOURCE, CURRENT_ROOT);
        var columns = List.of("username", "profile_id", "profile_bio");

        var result = detector.detectAttributes(columns);

        assertThat(result).hasSize(2);
        assertThat(result.get("profile"))
            .isInstanceOf(DetectedAttribute.OneToOneRoot.class)
            .extracting(a -> (DetectedAttribute.OneToOneRoot) a)
            .satisfies(attr -> {
                assertThat(attr.name()).isEqualTo("profile");
                assertThat(attr.targetRootName()).isEqualTo("user_profiles");
                assertThat(attr.subAttributes()).hasSize(2);
                assertThat(attr.subAttributes().get("bio"))
                    .isInstanceOf(DetectedAttribute.Basic.class);
            });
    }

    @Test
    @DisplayName("handler priority: overrides take precedence over detection")
    void handlerPriority() {
        List<SchemaOverride> overrides = List.of(
            new SchemaOverride.CompositeAttributeOverride(
                "address",
                List.of("address_street", "address_city"),
                List.of()
            )
        );
        var detector = new AttributeTypeDetector(Set.of(), SNAKE_CASE, overrides, ";", DATA_SOURCE, CURRENT_ROOT);
        var columns = List.of("address_street", "address_city", "name");

        var result = detector.detectAttributes(columns);

        // Override should claim address columns explicitly as composite
        assertThat(result).hasSize(2);
        assertThat(result.get("address"))
            .isInstanceOf(DetectedAttribute.Composite.class);
        assertThat(result.get("name"))
            .isInstanceOf(DetectedAttribute.Basic.class);
    }

    @Test
    @DisplayName("detects one-to-one root when prefix_id exists but prefix is not available root")
    void detectOneToOneRoot() {
        var detector = new AttributeTypeDetector(Set.of("user"), SNAKE_CASE, List.of(), ";", DATA_SOURCE, CURRENT_ROOT);
        var columns = List.of("user_id", "profile_id", "profile_bio", "profile_avatar");

        var result = detector.detectAttributes(columns);

        assertThat(result).hasSize(2);
        assertThat(result.get("userId"))
            .isInstanceOf(DetectedAttribute.SingularReference.class);
        assertThat(result.get("profile"))
            .isInstanceOf(DetectedAttribute.OneToOneRoot.class)
            .extracting(a -> (DetectedAttribute.OneToOneRoot) a)
            .satisfies(attr -> {
                assertThat(attr.name()).isEqualTo("profile");
                assertThat(attr.targetRootName()).isEqualTo("profile");
                assertThat(attr.subAttributes()).hasSize(3);
            });
    }
}
