package com.rorm.dataimport.pipeline;

import com.rorm.dataimport.attribute.DetectedAttribute;
import com.rorm.dataimport.hierarchical.JsonDataSource;
import com.rorm.dataimport.naming.NamingStyleDetector;
import com.rorm.dataimport.source.CsvDataSource;
import com.rorm.dataimport.type.DataTypeDetector;
import com.rorm.metamodel.Attribute;
import com.rorm.metamodel.BasicAttribute;
import com.rorm.metamodel.SingularReferenceAttribute;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for mixed source detection scenarios involving both CSV and JSON/YAML data sources.
 * These tests verify that roots from different source types can reference each other.
 */
@SpringBootTest(classes = {
    ModelSpaceDetector.class,
    NamingStyleDetector.class,
    SchemaDetector.class,
    MetamodelConverter.class,
    DataTypeDetector.class,
    FlatDetectionStrategy.class,
    HierarchicalDetectionStrategy.class,
    com.rorm.dataimport.hierarchical.HierarchicalSchemaConverter.class
})
@DisplayName("Mixed Source Detection")
class MixedSourceDetectionTest {

    @TempDir
    Path tempDir;

    @Autowired
    private ModelSpaceDetector modelSpaceDetector;

    @Autowired
    private MetamodelConverter metamodelConverter;

    @Nested
    @DisplayName("Cross-source reference detection")
    class CrossSourceReferenceDetection {

        @Test
        @DisplayName("CSV with user_id should detect reference to JSON users root")
        void csvShouldDetectReferenceToJsonRoot() throws Exception {
            // JSON file defining users
            var usersJson = tempDir.resolve("users.json");
            Files.writeString(usersJson, """
                [
                    {"id": 1, "name": "Alice", "email": "alice@example.com"},
                    {"id": 2, "name": "Bob", "email": "bob@example.com"}
                ]
                """);

            // CSV file with orders referencing users
            var ordersCsv = tempDir.resolve("orders.csv");
            Files.writeString(ordersCsv, """
                order_number,user_id,total
                ORD-001,1,100.00
                ORD-002,2,250.50
                """);

            var usersSource = new JsonDataSource(usersJson);
            var ordersSource = new CsvDataSource(ordersCsv);

            var detectedSchema = modelSpaceDetector.detect(
                List.of(usersSource, ordersSource),
                Map.of(),
                ","
            );

            // Verify orders root has a reference attribute to users
            var ordersRoot = detectedSchema.roots().get("orders");
            assertThat(ordersRoot).isNotNull();
            assertThat(ordersRoot.attributes())
                .containsKey("userId");

            var userIdAttr = ordersRoot.attributes().get("userId");
            assertThat(userIdAttr)
                .isInstanceOf(DetectedAttribute.SingularReference.class);

            var userIdRef = (DetectedAttribute.SingularReference) userIdAttr;
            assertThat(userIdRef.targetRootName()).isEqualTo("users");

            usersSource.close();
            ordersSource.close();
        }

        @Test
        @DisplayName("JSON with order_id should detect reference to CSV orders root")
        void jsonShouldDetectReferenceToCsvRoot() throws Exception {
            // CSV file defining orders
            var ordersCsv = tempDir.resolve("orders.csv");
            Files.writeString(ordersCsv, """
                order_number,total
                ORD-001,100.00
                ORD-002,250.50
                """);

            // JSON file with shipments referencing orders
            var shipmentsJson = tempDir.resolve("shipments.json");
            Files.writeString(shipmentsJson, """
                [
                    {"id": 1, "order_id": 1, "tracking_number": "TRK001"},
                    {"id": 2, "order_id": 2, "tracking_number": "TRK002"}
                ]
                """);

            var ordersSource = new CsvDataSource(ordersCsv);
            var shipmentsSource = new JsonDataSource(shipmentsJson);

            var detectedSchema = modelSpaceDetector.detect(
                List.of(ordersSource, shipmentsSource),
                Map.of(),
                ","
            );

            // Verify shipments root has a reference attribute to orders
            var shipmentsRoot = detectedSchema.roots().get("shipments");
            assertThat(shipmentsRoot).isNotNull();
            assertThat(shipmentsRoot.attributes())
                .containsKey("orderId");

            var orderIdAttr = shipmentsRoot.attributes().get("orderId");
            assertThat(orderIdAttr)
                .isInstanceOf(DetectedAttribute.SingularReference.class);

            var orderIdRef = (DetectedAttribute.SingularReference) orderIdAttr;
            assertThat(orderIdRef.targetRootName()).isEqualTo("orders");

            ordersSource.close();
            shipmentsSource.close();
        }

        @Test
        @DisplayName("Both CSV and JSON sources should detect mutual references")
        void mutualCrossSourceReferences() throws Exception {
            // JSON file defining categories
            var categoriesJson = tempDir.resolve("categories.json");
            Files.writeString(categoriesJson, """
                [
                    {"id": 1, "name": "Electronics"},
                    {"id": 2, "name": "Books"}
                ]
                """);

            // CSV file with products referencing categories
            var productsCsv = tempDir.resolve("products.csv");
            Files.writeString(productsCsv, """
                name,category_id,price
                Laptop,1,999.99
                Novel,2,19.99
                """);

            // JSON file with reviews referencing products
            var reviewsJson = tempDir.resolve("reviews.json");
            Files.writeString(reviewsJson, """
                [
                    {"id": 1, "product_id": 1, "rating": 5, "comment": "Great!"},
                    {"id": 2, "product_id": 2, "rating": 4, "comment": "Good read"}
                ]
                """);

            var categoriesSource = new JsonDataSource(categoriesJson);
            var productsSource = new CsvDataSource(productsCsv);
            var reviewsSource = new JsonDataSource(reviewsJson);

            var detectedSchema = modelSpaceDetector.detect(
                List.of(categoriesSource, productsSource, reviewsSource),
                Map.of(),
                ","
            );

            // Verify products has reference to categories
            var productsRoot = detectedSchema.roots().get("products");
            assertThat(productsRoot.attributes().get("categoryId"))
                .isInstanceOf(DetectedAttribute.SingularReference.class)
                .extracting(attr -> ((DetectedAttribute.SingularReference) attr).targetRootName())
                .isEqualTo("categories");

            // Verify reviews has reference to products
            var reviewsRoot = detectedSchema.roots().get("reviews");
            assertThat(reviewsRoot.attributes().get("productId"))
                .isInstanceOf(DetectedAttribute.SingularReference.class)
                .extracting(attr -> ((DetectedAttribute.SingularReference) attr).targetRootName())
                .isEqualTo("products");

            categoriesSource.close();
            productsSource.close();
            reviewsSource.close();
        }
    }

    @Nested
    @DisplayName("Hierarchical source structure detection")
    class HierarchicalStructureDetection {

        @Test
        @DisplayName("JSON with nested objects creates separate roots")
        void jsonNestedObjectsCreateSeparateRoots() throws Exception {
            var usersJson = tempDir.resolve("users.json");
            Files.writeString(usersJson, """
                [
                    {
                        "id": 1,
                        "name": "Alice",
                        "profile": {"id": 101, "bio": "Developer", "avatar": "alice.png"}
                    }
                ]
                """);

            var usersSource = new JsonDataSource(usersJson);

            var detectedSchema = modelSpaceDetector.detect(
                List.of(usersSource),
                Map.of(),
                ","
            );

            // Should have two roots: users and users_profile
            assertThat(detectedSchema.roots()).containsKeys("users", "users_profile");

            var usersRoot = detectedSchema.roots().get("users");
            assertThat(usersRoot.attributes()).containsKeys("id", "name", "profile");

            var profileAttr = usersRoot.attributes().get("profile");
            assertThat(profileAttr).isInstanceOf(DetectedAttribute.SingularReference.class);

            var profileRoot = detectedSchema.roots().get("users_profile");
            assertThat(profileRoot.attributes()).containsKeys("id", "bio", "avatar");

            usersSource.close();
        }

        @Test
        @DisplayName("JSON with array of objects creates child root")
        void jsonArrayOfObjectsCreatesChildRoot() throws Exception {
            var ordersJson = tempDir.resolve("orders.json");
            Files.writeString(ordersJson, """
                [
                    {
                        "id": 1,
                        "total": 299.99,
                        "items": [
                            {"id": 10, "product": "Laptop", "quantity": 1},
                            {"id": 11, "product": "Mouse", "quantity": 2}
                        ]
                    }
                ]
                """);

            var ordersSource = new JsonDataSource(ordersJson);

            var detectedSchema = modelSpaceDetector.detect(
                List.of(ordersSource),
                Map.of(),
                ","
            );

            // Should have two roots: orders and orders_item
            assertThat(detectedSchema.roots()).containsKeys("orders", "orders_item");

            var ordersRoot = detectedSchema.roots().get("orders");
            assertThat(ordersRoot.attributes()).containsKeys("id", "total", "items");

            var itemsAttr = ordersRoot.attributes().get("items");
            assertThat(itemsAttr).isInstanceOf(DetectedAttribute.PluralReference.class);

            var itemsRef = (DetectedAttribute.PluralReference) itemsAttr;
            assertThat(itemsRef.targetRootName()).isEqualTo("orders_item");

            ordersSource.close();
        }

        @Test
        @DisplayName("JSON composite attributes remain embedded")
        void jsonCompositeAttributesRemainEmbedded() throws Exception {
            var usersJson = tempDir.resolve("users.json");
            Files.writeString(usersJson, """
                [
                    {
                        "id": 1,
                        "name": "Alice",
                        "address": {"street": "123 Main St", "city": "NYC", "zip": "10001"}
                    }
                ]
                """);

            var usersSource = new JsonDataSource(usersJson);

            var detectedSchema = modelSpaceDetector.detect(
                List.of(usersSource),
                Map.of(),
                ","
            );

            // Should only have users root (address is composite, no ID)
            assertThat(detectedSchema.roots()).containsOnlyKeys("users");

            var usersRoot = detectedSchema.roots().get("users");
            assertThat(usersRoot.attributes()).containsKeys("id", "name", "address");

            var addressAttr = usersRoot.attributes().get("address");
            assertThat(addressAttr).isInstanceOf(DetectedAttribute.Composite.class);

            var composite = (DetectedAttribute.Composite) addressAttr;
            assertThat(composite.subAttributes()).containsKeys("street", "city", "zip");

            usersSource.close();
        }
    }

    @Nested
    @DisplayName("Mixed source ModelSpace conversion")
    class MixedSourceModelSpaceConversion {

        @Test
        @DisplayName("Mixed sources produce correct ModelSpace with cross-references")
        void mixedSourcesProduceCorrectModelSpace() throws Exception {
            var usersJson = tempDir.resolve("users.json");
            Files.writeString(usersJson, """
                [
                    {"id": 1, "name": "Alice"},
                    {"id": 2, "name": "Bob"}
                ]
                """);

            var ordersCsv = tempDir.resolve("orders.csv");
            Files.writeString(ordersCsv, """
                order_number,user_id,total
                ORD-001,1,100.00
                ORD-002,2,250.50
                """);

            var usersSource = new JsonDataSource(usersJson);
            var ordersSource = new CsvDataSource(ordersCsv);

            var modelSpace = metamodelConverter.convertToModelSpace(
                modelSpaceDetector.detect(
                    List.of(usersSource, ordersSource),
                    Map.of(),
                    ","
                )
            );

            assertThat(modelSpace.roots()).hasSize(2);

            var usersRoot = modelSpace.roots().stream()
                .filter(r -> r.primaryTableName().equals("users"))
                .findFirst()
                .orElseThrow();

            var ordersRoot = modelSpace.roots().stream()
                .filter(r -> r.primaryTableName().equals("orders"))
                .findFirst()
                .orElseThrow();

            // Verify users root has basic attributes
            assertThat(usersRoot.attributes())
                .filteredOn(BasicAttribute.class::isInstance)
                .extracting(Attribute::name)
                .contains("id", "name");

            // Verify orders root has reference to users
            var userIdAttr = ordersRoot.attributes().stream()
                .filter(SingularReferenceAttribute.class::isInstance)
                .map(SingularReferenceAttribute.class::cast)
                .filter(a -> a.name().equals("userId"))
                .findFirst()
                .orElseThrow();

            assertThat(userIdAttr.targetRoot()).isEqualTo(usersRoot);

            usersSource.close();
            ordersSource.close();
        }

        @Test
        @DisplayName("Hierarchical roots with cross-references to flat roots")
        void hierarchicalRootsWithCrossReferencesToFlatRoots() throws Exception {
            var categoriesCsv = tempDir.resolve("categories.csv");
            Files.writeString(categoriesCsv, """
                name,description
                Electronics,Electronic devices
                Books,Physical and digital books
                """);

            var productsJson = tempDir.resolve("products.json");
            Files.writeString(productsJson, """
                [
                    {
                        "id": 1,
                        "name": "Laptop",
                        "category_id": 1,
                        "specs": {"id": 101, "cpu": "i7", "ram": "16GB"}
                    },
                    {
                        "id": 2,
                        "name": "Novel",
                        "category_id": 2,
                        "specs": {"id": 102, "pages": "300", "format": "hardcover"}
                    }
                ]
                """);

            var categoriesSource = new CsvDataSource(categoriesCsv);
            var productsSource = new JsonDataSource(productsJson);

            var modelSpace = metamodelConverter.convertToModelSpace(
                modelSpaceDetector.detect(
                    List.of(categoriesSource, productsSource),
                    Map.of(),
                    ","
                )
            );

            // Should have 3 roots: categories, products, products_spec
            assertThat(modelSpace.roots()).hasSize(3);

            var productsRoot = modelSpace.roots().stream()
                .filter(r -> r.primaryTableName().equals("products"))
                .findFirst()
                .orElseThrow();

            // Verify products has reference to categories (cross-source)
            var categoryRef = productsRoot.attributes().stream()
                .filter(SingularReferenceAttribute.class::isInstance)
                .map(SingularReferenceAttribute.class::cast)
                .filter(a -> a.name().equals("categoryId"))
                .findFirst();

            assertThat(categoryRef).isPresent();
            assertThat(categoryRef.get().targetRoot().primaryTableName()).isEqualTo("categories");

            // Verify products has reference to specs (hierarchical child)
            var specsRef = productsRoot.attributes().stream()
                .filter(SingularReferenceAttribute.class::isInstance)
                .map(SingularReferenceAttribute.class::cast)
                .filter(a -> a.name().equals("specs"))
                .findFirst();

            assertThat(specsRef).isPresent();
            assertThat(specsRef.get().targetRoot().primaryTableName()).isEqualTo("products_spec");

            categoriesSource.close();
            productsSource.close();
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Same root name in different source types throws exception")
        void sameRootNameInDifferentSourceTypesThrowsException() throws Exception {
            var usersCsv = tempDir.resolve("users.csv");
            Files.writeString(usersCsv, """
                name,email
                Alice,alice@example.com
                """);

            var usersJson = tempDir.resolve("users.json");
            Files.writeString(usersJson, """
                [{"id": 1, "name": "Bob"}]
                """);

            var csvSource = new CsvDataSource(usersCsv);
            var jsonSource = new JsonDataSource(usersJson);

            assertThat(
                org.assertj.core.api.Assertions.catchThrowable(() ->
                    modelSpaceDetector.detect(
                        List.of(csvSource, jsonSource),
                        Map.of(),
                        ","
                    )
                )
            ).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate root name");

            csvSource.close();
            jsonSource.close();
        }

        @Test
        @DisplayName("Reference to non-existent root remains as basic attribute")
        void referenceToNonExistentRootRemainsAsBasicAttribute() throws Exception {
            var ordersCsv = tempDir.resolve("orders.csv");
            Files.writeString(ordersCsv, """
                order_number,customer_id,total
                ORD-001,1,100.00
                """);

            var ordersSource = new CsvDataSource(ordersCsv);

            var detectedSchema = modelSpaceDetector.detect(
                List.of(ordersSource),
                Map.of(),
                ","
            );

            // customer_id should NOT be detected as reference (no "customer" or "customers" root)
            var ordersRoot = detectedSchema.roots().get("orders");
            var customerIdAttr = ordersRoot.attributes().get("customerId");

            assertThat(customerIdAttr).isInstanceOf(DetectedAttribute.Basic.class);

            ordersSource.close();
        }

        @Test
        @DisplayName("Empty mixed sources handled gracefully")
        void emptyMixedSourcesHandledGracefully() throws Exception {
            var emptyCsv = tempDir.resolve("empty.csv");
            Files.writeString(emptyCsv, """
                name,value
                """);

            var emptyJson = tempDir.resolve("items.json");
            Files.writeString(emptyJson, "[]");

            var csvSource = new CsvDataSource(emptyCsv);
            var jsonSource = new JsonDataSource(emptyJson);

            var detectedSchema = modelSpaceDetector.detect(
                List.of(csvSource, jsonSource),
                Map.of(),
                ","
            );

            assertThat(detectedSchema.roots()).containsKeys("empty", "items");

            csvSource.close();
            jsonSource.close();
        }
    }
}
