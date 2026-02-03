package com.rorm.dataimport.pipeline;

import com.rorm.dataimport.naming.NamingStyleDetector;
import com.rorm.dataimport.override.SchemaOverride;
import com.rorm.dataimport.source.CsvDataSource;
import com.rorm.dataimport.type.DataTypeDetector;
import com.rorm.metamodel.Attribute;
import com.rorm.metamodel.AttributeLocation;
import com.rorm.metamodel.BasicAttribute;
import com.rorm.metamodel.DataType.NumericType;
import com.rorm.metamodel.DataType.StringType;
import com.rorm.metamodel.Root;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

@SpringBootTest(classes = {
    ModelSpaceDetector.class,
    NamingStyleDetector.class,
    SchemaDetector.class,
    MetamodelConverter.class,
    DataTypeDetector.class
})
class ModelSpaceDetectorTest {

    @TempDir
    Path tempDir;
    @Autowired
    private ModelSpaceDetector modelSpaceDetector;
    @Autowired
    private MetamodelConverter metamodelConverter;

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
        var modelSpace = metamodelConverter.convertToModelSpace(modelSpaceDetector.detect(
            List.of(dataSource),
            Map.of(),
            ";"
        ));

        assertThat(modelSpace.roots()).hasSize(1);

        var root = modelSpace.roots().iterator().next();
        assertThat(root.primaryTableName()).isEqualTo("users");
        assertThat(root.attributes()).hasSize(4);

        var attributes = root.attributes().stream()
            .map(BasicAttribute.class::cast)
            .toList();

        assertThat(attributes)
            .extracting(BasicAttribute::name)
            .containsExactlyInAnyOrder("name", "email", "age", "id");

        assertThat(attributes)
            .extracting(attr -> attr.location().column())
            .containsExactlyInAnyOrder("name", "email", "age", "id");

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
        var modelSpace = metamodelConverter.convertToModelSpace(modelSpaceDetector.detect(
            List.of(dataSource),
            Map.of(),
            ";"
        ));

        var root = modelSpace.roots().iterator().next();
        assertThat(root.primaryTableName()).isEqualTo("products");

        var attributes = root.attributes().stream()
            .map(BasicAttribute.class::cast)
            .toList();

        assertThat(attributes)
            .extracting(Attribute::name)
            .containsExactlyInAnyOrder("id", "productName", "unitPrice", "stockQuantity");

        assertThat(attributes)
            .extracting(attr -> attr.location().column())
            .containsExactlyInAnyOrder("id", "product_name", "unit_price", "stock_quantity");

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

        var modelSpace = metamodelConverter.convertToModelSpace(modelSpaceDetector.detect(
            List.of(userSource, orderSource),
            Map.of(),
            ";"
        ));

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
            new SchemaOverride.BasicAttributeOverride("price", new NumericType(10, 2)),
            new SchemaOverride.BasicAttributeOverride("quantity", new NumericType(10, 0))
        );

        var dataSource = new CsvDataSource(csvFile);
        var modelSpace = metamodelConverter.convertToModelSpace(modelSpaceDetector.detect(
            List.of(dataSource),
            Map.of("products", overrides),
            ";"
        ));

        var root = modelSpace.roots().iterator().next();
        assertThat(root.attributes())
            .hasSize(4)
            .filteredOn(BasicAttribute.class::isInstance)
            .map(BasicAttribute.class::cast)
            .containsExactlyInAnyOrder(
                new BasicAttribute(
                    "name",
                    new AttributeLocation("products", "name"),
                    new StringType()
                ),
                new BasicAttribute(
                    "price",
                    new AttributeLocation("products", "price"),
                    new NumericType(10, 2)
                ),
                new BasicAttribute(
                    "quantity",
                    new AttributeLocation("products", "quantity"),
                    new NumericType(10, 0)
                ),
                new BasicAttribute(
                    "id",
                    new AttributeLocation("products", "id"),
                    new NumericType(19, 0)
                )
            );

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
        var modelSpace = metamodelConverter.convertToModelSpace(modelSpaceDetector.detect(
            List.of(dataSource),
            Map.of(),
            ";"
        ));

        var root = modelSpace.roots().iterator().next();
        assertThat(root.primaryTableName()).isEqualTo("empty");
        assertThat(root.attributes())
            .hasSize(3)
            .extracting(Attribute::name)
            .containsExactlyInAnyOrder("name", "email", "id");

        dataSource.close();
    }
}
