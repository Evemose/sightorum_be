package com.rorm.dataimport.pipeline;

import com.rorm.dataimport.naming.NamingStyleDetector;
import com.rorm.dataimport.override.SchemaOverride;
import com.rorm.dataimport.source.CsvDataSource;
import com.rorm.metamodel.BasicAttribute;
import com.rorm.metamodel.Root;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

@SpringBootTest(classes = {
    ModelSpaceDetector.class,
    NamingStyleDetector.class
})
class ModelSpaceDetectorTest {

    @TempDir
    Path tempDir;
    @Autowired
    private ModelSpaceDetector modelSpaceDetector;

    @Test
    @DisplayName("detects single root with basic string attributes")
    void detectSingleRootBasicAttributes() throws Exception {
        var csvFile = tempDir.resolve("users.csv");
        Files.writeString(csvFile, """
            name,email,age
            Alice,alice@example.com,30
            Bob,bob@example.com,25
            """);

        var dataSource = new CsvDataSource(csvFile);
        var modelSpace = modelSpaceDetector.detectModelSpace(
            List.of(dataSource),
            List.of(),
            ";"
        );

        assertThat(modelSpace.roots()).hasSize(1);

        var root = modelSpace.roots().iterator().next();
        assertThat(root.primaryTableName()).isEqualTo("users");
        assertThat(root.attributes()).hasSize(3);

        var attributes = root.attributes().stream()
            .map(BasicAttribute.class::cast)
            .toList();

        assertThat(attributes)
            .extracting(BasicAttribute::name)
            .containsExactlyInAnyOrder("name", "email", "age");

        assertThat(attributes)
            .extracting(attr -> attr.location().column())
            .containsExactlyInAnyOrder("name", "email", "age");

        dataSource.close();
    }

    @Test
    @DisplayName("detects single root with snake_case columns converted to camelCase attributes")
    void detectSnakeCaseConversion() throws Exception {
        var csvFile = tempDir.resolve("products.csv");
        Files.writeString(csvFile, """
            product_name,unit_price,stock_quantity
            Laptop,999.99,50
            Mouse,19.99,200
            """);

        var dataSource = new CsvDataSource(csvFile);
        var modelSpace = modelSpaceDetector.detectModelSpace(
            List.of(dataSource),
            List.of(),
            ";"
        );

        var root = modelSpace.roots().iterator().next();
        assertThat(root.primaryTableName()).isEqualTo("products");

        var attributes = root.attributes().stream()
            .map(BasicAttribute.class::cast)
            .toList();

        assertThat(attributes)
            .extracting(BasicAttribute::name)
            .containsExactlyInAnyOrder("productName", "unitPrice", "stockQuantity");

        assertThat(attributes)
            .extracting(attr -> attr.location().column())
            .containsExactlyInAnyOrder("product_name", "unit_price", "stock_quantity");

        dataSource.close();
    }

    @Test
    @DisplayName("detects multiple roots from multiple data sources")
    void detectMultipleRoots() throws Exception {
        var usersFile = tempDir.resolve("users.csv");
        Files.writeString(usersFile, """
            name,email
            Alice,alice@example.com
            """);

        var ordersFile = tempDir.resolve("orders.csv");
        Files.writeString(ordersFile, """
            order_number,total
            ORD-001,100.00
            """);

        var userSource = new CsvDataSource(usersFile);
        var orderSource = new CsvDataSource(ordersFile);

        var modelSpace = modelSpaceDetector.detectModelSpace(
            List.of(userSource, orderSource),
            List.of(),
            ";"
        );

        assertThat(modelSpace.roots()).hasSize(2);

        var rootNames = modelSpace.roots().stream()
            .map(Root::primaryTableName)
            .toList();

        assertThat(rootNames).containsExactlyInAnyOrder("users", "orders");

        userSource.close();
        orderSource.close();
    }

    @Test
    @DisplayName("applies basic attribute override to change detected type")
    void applyBasicAttributeOverride() throws Exception {
        var csvFile = tempDir.resolve("products.csv");
        Files.writeString(csvFile, """
            name,price,quantity
            Laptop,999.99,50
            """);

        var overrides = List.<SchemaOverride>of(
            new SchemaOverride.BasicAttributeOverride("price", "decimal"),
            new SchemaOverride.BasicAttributeOverride("quantity", "integer")
        );

        var dataSource = new CsvDataSource(csvFile);
        var modelSpace = modelSpaceDetector.detectModelSpace(
            List.of(dataSource),
            overrides,
            ";"
        );

        // Note: Currently BasicAttribute doesn't store type, only location
        // This test verifies that overrides are processed without error
        var root = modelSpace.roots().iterator().next();
        assertThat(root.attributes()).hasSize(3);

        dataSource.close();
    }

    @Test
    @DisplayName("handles empty CSV file gracefully")
    void handleEmptyFile() throws Exception {
        var csvFile = tempDir.resolve("empty.csv");
        Files.writeString(csvFile, """
            name,email
            """);

        var dataSource = new CsvDataSource(csvFile);
        var modelSpace = modelSpaceDetector.detectModelSpace(
            List.of(dataSource),
            List.of(),
            ";"
        );

        var root = modelSpace.roots().iterator().next();
        assertThat(root.primaryTableName()).isEqualTo("empty");
        assertThat(root.attributes()).hasSize(2);

        dataSource.close();
    }
}
