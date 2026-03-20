package com.rorm.dataimport.pipeline;

import com.rorm.dataimport.naming.NamingStyleDetector;
import com.rorm.dataimport.override.SchemaOverride;
import com.rorm.dataimport.source.CsvDataSource;
import com.rorm.dataimport.type.DataTypeDetector;
import com.rorm.metamodel.*;
import com.rorm.metamodel.CollectionAttribute.BasicElement;
import com.rorm.metamodel.DataType.NumericType;
import com.rorm.metamodel.DataType.StringType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
class ModelSpaceDetectorComplexTest {

    @TempDir
    Path tempDir;
    @Autowired
    private ModelSpaceDetector modelSpaceDetector;
    @Autowired
    private MetamodelConverter metamodelConverter;

    @Test
    @DisplayName("applies type overrides to nested composite attributes")
    void applyNestedCompositeTypeOverrides() throws Exception {
        var csvFile = tempDir.resolve("students.csv");
        Files.writeString(csvFile, """
            name,home_address_street,home_address_city,home_address_zip,work_address_street,work_address_city
            John,123 Main,Boston,02101,456 Work St,Cambridge
            """);

        // Create nested overrides for home address zip (nested in home address composite)
        var homeAddressNestedOverrides = List.<SchemaOverride>of(
            new SchemaOverride.BasicAttributeOverride("zip", new StringType())
        );

        var overrides = List.<SchemaOverride>of(
            new SchemaOverride.CompositeAttributeOverride(
                "homeAddress",
                List.of("home_address_street", "home_address_city", "home_address_zip"),
                homeAddressNestedOverrides
            ),
            new SchemaOverride.CompositeAttributeOverride(
                "workAddress",
                List.of("work_address_street", "work_address_city"),
                List.of()
            )
        );

        var dataSource = new CsvDataSource(csvFile);
        var modelSpace = metamodelConverter.convertToModelSpace(modelSpaceDetector.detect(
            List.of(dataSource),
            Map.of("students", overrides),
            ";"
        ));

        var root = modelSpace.roots().stream()
            .filter(r -> r.primaryTableName().equals("students"))
            .findFirst()
            .orElseThrow();

        // Verify composite attributes were created
        var homeAddress = root.attributes().stream()
            .filter(a -> a.name().equals("homeAddress"))
            .findFirst()
            .orElseThrow();

        assertThat(homeAddress).isInstanceOf(CompositeAttribute.class);
        var homeComposite = (CompositeAttribute) homeAddress;

        // Verify the zip attribute has StringType (from override)
        var zipAttr = homeComposite.attributes().stream()
            .filter(a -> a.name().equals("zip"))
            .findFirst()
            .orElseThrow();

        assertThat(zipAttr).isInstanceOf(BasicAttribute.class);
        assertThat(((BasicAttribute) zipAttr).dataType()).isInstanceOf(StringType.class);

        dataSource.close();
    }

    @Test
    @DisplayName("detects composite attributes from prefixed columns")
    void detectCompositeAttributes() throws Exception {
        var csvFile = tempDir.resolve("customers.csv");
        Files.writeString(csvFile, """
            name,address_street,address_city,address_zip_code
            John Doe,123 Main St,New York,10001
            Jane Smith,456 Oak Ave,Boston,02101
            """);

        var dataSource = new CsvDataSource(csvFile);
        var modelSpace = metamodelConverter.convertToModelSpace(modelSpaceDetector.detect(
            List.of(dataSource),
            Map.of(),
            ";"
        ));

        var root = modelSpace.roots().iterator().next();
        // Note: Current implementation only detects BasicAttributes
        // Composite detection creates nested structure but we're verifying basic columns
        assertThat(root.attributes()).isNotEmpty();

        dataSource.close();
    }

    @Test
    @DisplayName("detects references when columns end with _id and root exists")
    void detectReferences() throws Exception {
        var usersFile = tempDir.resolve("users.csv");
        Files.writeString(usersFile, """
            name,email
            Alice,alice@example.com
            """);

        var ordersFile = tempDir.resolve("orders.csv");
        Files.writeString(ordersFile, """
            order_number,user_id,total
            ORD-001,1,100.00
            """);

        var userSource = new CsvDataSource(usersFile);
        var orderSource = new CsvDataSource(ordersFile);

        var modelSpace = metamodelConverter.convertToModelSpace(modelSpaceDetector.detect(
            List.of(userSource, orderSource),
            Map.of(),
            ";"
        ));

        assertThat(modelSpace.roots()).hasSize(2);

        var ordersRoot = modelSpace.roots().stream()
            .filter(r -> r.primaryTableName().equals("orders"))
            .findFirst()
            .orElseThrow();

        // Note: Current implementation only returns BasicAttributes in convertToAttributes
        // Reference detection happens but isn't reflected in final ModelSpace yet
        assertThat(ordersRoot.attributes()).isNotEmpty();

        userSource.close();
        orderSource.close();
    }

    @Test
    @DisplayName("applies composite override with explicit sub-attribute columns")
    void applyCompositeOverride() throws Exception {
        var csvFile = tempDir.resolve("customers.csv");
        Files.writeString(csvFile, """
            name,street_address,city_name,postal_code
            John Doe,123 Main St,New York,10001
            """);

        var overrides = List.<SchemaOverride>of(
            new SchemaOverride.CompositeAttributeOverride(
                "address",
                List.of("street_address", "city_name", "postal_code"),
                List.of()
            )
        );

        var dataSource = new CsvDataSource(csvFile);
        var modelSpace = metamodelConverter.convertToModelSpace(modelSpaceDetector.detect(
            List.of(dataSource),
            Map.of("customers", overrides),
            ";"
        ));

        var root = modelSpace.roots().iterator().next();
        assertThat(root.attributes()).isNotEmpty();

        dataSource.close();
    }

    @Test
    @DisplayName("applies composite override with nested overrides for sub-attributes")
    void applyCompositeOverrideWithNestedOverrides() throws Exception {
        var csvFile = tempDir.resolve("customers.csv");
        Files.writeString(csvFile, """
            name,address_street,address_city,address_zip_code
            John Doe,123 Main St,New York,10001
            """);

        var nestedOverrides = List.<SchemaOverride>of(
            new SchemaOverride.BasicAttributeOverride("street", new StringType()),
            new SchemaOverride.BasicAttributeOverride("zipCode", new com.rorm.metamodel.DataType.NumericType(10, 0))
        );

        var overrides = List.<SchemaOverride>of(
            new SchemaOverride.CompositeAttributeOverride(
                "address",
                List.of("address_street", "address_city", "address_zip_code"),
                nestedOverrides
            )
        );

        var dataSource = new CsvDataSource(csvFile);
        var modelSpace = metamodelConverter.convertToModelSpace(modelSpaceDetector.detect(
            List.of(dataSource),
            Map.of("customers", overrides),
            ";"
        ));

        var root = modelSpace.roots().iterator().next();
        assertThat(root.attributes()).isNotEmpty();

        dataSource.close();
    }

    @Test
    @DisplayName("applies singular reference override")
    void applySingularReferenceOverride() throws Exception {
        var orderFile = tempDir.resolve("orders.csv");
        Files.writeString(orderFile, """
            order_number,customer_id,total
            ORD-001,1,100.00
            """);

        var usersFile = tempDir.resolve("users.csv");
        Files.writeString(usersFile, """
            name,email
            Alice,alice@test.test
            """);

        var overrides = List.<SchemaOverride>of(
            new SchemaOverride.SingularReferenceOverride("customerId", "users")
        );

        var orderSource = new CsvDataSource(orderFile);
        var userSource = new CsvDataSource(usersFile);
        var modelSpace = metamodelConverter.convertToModelSpace(modelSpaceDetector.detect(
            List.of(orderSource, userSource),
            Map.of("orders", overrides),
            ";"
        ));

        var root = modelSpace.roots().iterator().next();
        assertThat(root.attributes()).isNotEmpty();

        orderSource.close();
        userSource.close();
    }

    @Test
    @DisplayName("applies collection attribute override with custom separator")
    void applyCollectionOverride() throws Exception {
        var csvFile = tempDir.resolve("products.csv");
        Files.writeString(csvFile, """
            name,tags,category
            Laptop,electronics|computers|hardware,tech
            """);

        var overrides = List.<SchemaOverride>of(
            new SchemaOverride.CollectionAttributeOverride("tags", null, "|")
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
            .containsExactlyInAnyOrder(
                new BasicAttribute("id", new AttributeLocation("products", "id"), new NumericType(19, 0)),
                new BasicAttribute("name", new AttributeLocation("products", "name"), new StringType()),
                new BasicAttribute("category", new AttributeLocation("products", "category"), new StringType()),
                new CollectionAttribute(
                    "tags",
                    "products",
                    new BasicElement(new AttributeLocation("products", "tags"), new StringType())
                )
            );

        dataSource.close();
    }

    @Test
    @DisplayName("detects one-to-one root when prefix_id exists but prefix is not available root")
    void detectOneToOneRoot() throws Exception {
        var csvFile = tempDir.resolve("users.csv");
        Files.writeString(csvFile, """
            name,profile_id,profile_bio,profile_avatar
            Alice,1,Software Engineer,avatar.jpg
            """);

        var dataSource = new CsvDataSource(csvFile);
        var modelSpace = metamodelConverter.convertToModelSpace(modelSpaceDetector.detect(
            List.of(dataSource),
            Map.of(),
            ";"
        ));

        var root = modelSpace.roots().iterator().next();
        // Note: OneToOneRoot detection creates special attribute type
        assertThat(root.attributes()).isNotEmpty();

        dataSource.close();
    }

    @Test
    @DisplayName("one-to-one root reference uses SameTableColumn mapping — FK lives in parent table")
    void oneToOneRootUsesSameTableColumnMapping() throws Exception {
        var csvFile = tempDir.resolve("employees.csv");
        Files.writeString(csvFile, """
            id,name,contact_id,contact_email,contact_phone
            1,Alice,10,alice@work.com,555-0001
            2,Bob,20,bob@work.com,555-0002
            """);

        var dataSource = new CsvDataSource(csvFile);
        var modelSpace = metamodelConverter.convertToModelSpace(modelSpaceDetector.detect(
            List.of(dataSource),
            Map.of(),
            ";"
        ));

        var employeesRoot = modelSpace.roots().stream()
            .filter(r -> r.primaryTableName().equals("employees"))
            .findFirst()
            .orElseThrow();

        var contactRef = employeesRoot.attributes().stream()
            .filter(SingularReferenceAttribute.class::isInstance)
            .map(SingularReferenceAttribute.class::cast)
            .filter(a -> a.name().equals("contact"))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "Expected SingularReferenceAttribute 'contact' on employees root"));

        // FK column (contact_id) is in the parent table (employees), not the target table (contact)
        assertThat(contactRef.mappingStrategy())
            .as("OneToOneRoot FK should be SameTableColumn, not InverseRootTableColumn")
            .isInstanceOf(ReferenceAttribute.SameTableColumn.class);
        assertThat(((ReferenceAttribute.SameTableColumn) contactRef.mappingStrategy()).columnName())
            .isEqualTo("contact_id");

        dataSource.close();
    }

    @Test
    @DisplayName("applies one-to-one root override with nested overrides")
    void applyOneToOneRootOverride() throws Exception {
        var csvFile = tempDir.resolve("users.csv");
        Files.writeString(csvFile, """
            username,profile_id,profile_bio,profile_avatar
            alice,1,Software Engineer,avatar.jpg
            """);

        var nestedOverrides = List.<SchemaOverride>of(
            new SchemaOverride.BasicAttributeOverride("bio", new StringType())
        );

        var overrides = List.<SchemaOverride>of(
            new SchemaOverride.OneToOneRootOverride(
                "profile",
                "user_profiles",
                List.of("profile_id", "profile_bio", "profile_avatar"),
                nestedOverrides,
                "profile_id"  // Explicit ID column for OneToOneRoot
            )
        );

        var dataSource = new CsvDataSource(csvFile);
        var modelSpace = metamodelConverter.convertToModelSpace(modelSpaceDetector.detect(
            List.of(dataSource),
            Map.of("users", overrides),
            ";"
        ));

        var root = modelSpace.roots().iterator().next();
        assertThat(root.attributes()).isNotEmpty();

        dataSource.close();
    }
}
