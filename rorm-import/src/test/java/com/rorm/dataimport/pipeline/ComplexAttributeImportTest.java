package com.rorm.dataimport.pipeline;

import com.rorm.dataimport.hierarchical.JsonDataSource;
import com.rorm.dataimport.override.SchemaOverride.BasicAttributeOverride;
import com.rorm.dataimport.override.SchemaOverride.CollectionAttributeOverride;
import com.rorm.dataimport.source.CsvDataSource;
import com.rorm.metamodel.DataType;
import com.rorm.metamodel.DataType.StringType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.MAP;

/**
 * Black-box integration tests for importing complex attribute types.
 * Tests verify that all attribute types (composites, one-to-one roots, collections, references)
 * are correctly imported into the database.
 */
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
@DisplayName("Complex Attribute Import")
class ComplexAttributeImportTest extends AbstractImportTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("imports composite/embeddable attributes from grouped columns")
    void importCompositeAttributes() throws Exception {
        // CSV with address_* columns should create an embeddable Address composite
        var csvFile = tempDir.resolve("customers.csv");
        Files.writeString(csvFile, """
            id,name,address_street,address_city,address_zip
            1,Alice,123 Main St,Springfield,12345
            2,Bob,456 Oak Ave,Shelbyville,67890
            3,Charlie,789 Elm Rd,Capital City,11111
            """);

        var dataSource = new CsvDataSource(csvFile);
        var detectionResult = modelSpaceDetector.detect(
            List.of(dataSource),
            Map.of(),
            ";"
        );

        var schema = getSchemaName();
        var request = new ImportRequest(schema, List.of(dataSource), detectionResult);
        var result = awaitImportCompletion(importData(request));

        assertThat(result.totalRowsImported()).isEqualTo(3);

        // Verify data in database - composite columns should be imported
        var rows = jdbcTemplate.queryForList("SELECT * FROM %s.customers ORDER BY id".formatted(schema));
        assertThat(rows).hasSize(3);

        assertThat(rows.get(0))
            .containsEntry("name", "Alice")
            .containsEntry("address_street", "123 Main St")
            .containsEntry("address_city", "Springfield")
            .containsEntry("address_zip", 12345L);

        assertThat(rows.get(1))
            .containsEntry("name", "Bob")
            .containsEntry("address_street", "456 Oak Ave")
            .containsEntry("address_city", "Shelbyville")
            .containsEntry("address_zip", 67890L);

        assertThat(rows.get(2))
            .containsEntry("name", "Charlie")
            .containsEntry("address_street", "789 Elm Rd")
            .containsEntry("address_city", "Capital City")
            .containsEntry("address_zip", 11111L);

        dataSource.close();
    }

    @Test
    @DisplayName("imports one-to-one root with separate table for nested entity")
    void importOneToOneRoot() throws Exception {
        // CSV with contact_* columns including contact_id should create separate contact table
        var csvFile = tempDir.resolve("employees.csv");
        Files.writeString(csvFile, """
            id,name,department,contact_id,contact_email,contact_phone
            1,Alice,Engineering,1,alice@work.com,555-0001
            2,Bob,Sales,2,bob@work.com,555-0002
            3,Charlie,HR,3,charlie@work.com,555-0003
            """);

        var dataSource = new CsvDataSource(csvFile);
        var detectionResult = modelSpaceDetector.detect(
            List.of(dataSource),
            Map.of(),
            ";"
        );

        var schema = getSchemaName();
        var request = new ImportRequest(schema, List.of(dataSource), detectionResult);
        var result = awaitImportCompletion(importData(request));

        assertThat(result.totalRowsImported()).isEqualTo(3);

        // Verify main table data
        var employeeRows = jdbcTemplate.queryForList(
            "SELECT * FROM %s.employees ORDER BY id".formatted(schema)
        );
        assertThat(employeeRows).hasSize(3);

        assertThat(employeeRows)
            .first(MAP)
            .containsEntry("name", "Alice")
            .containsEntry("department", "Engineering");

        // Verify separate contact table exists and has data
        var contactRows = jdbcTemplate.queryForList(
            "SELECT * FROM %s.contact ORDER BY contact_id".formatted(schema)
        );
        assertThat(contactRows).hasSize(3);

        assertThat(contactRows)
            .first(MAP)
            .containsEntry("contact_id", 1L)
            .containsEntry("contact_email", "alice@work.com")
            .containsEntry("contact_phone", "555-0001");

        assertThat(contactRows)
            .element(1, MAP)
            .containsEntry("contact_id", 2L)
            .containsEntry("contact_email", "bob@work.com")
            .containsEntry("contact_phone", "555-0002");

        assertThat(contactRows)
            .element(2, MAP)
            .containsEntry("contact_id", 3L)
            .containsEntry("contact_email", "charlie@work.com")
            .containsEntry("contact_phone", "555-0003");

        dataSource.close();
    }

    @Test
    @DisplayName("imports collection attributes with separator")
    void importCollectionAttributes() throws Exception {
        // CSV with tags column containing semicolon-separated values
        var csvFile = tempDir.resolve("articles.csv");
        Files.writeString(csvFile, """
            id,title,tags
            1,"Java Basics","java;programming;tutorial"
            2,"Spring Boot Guide","spring;java;framework"
            3,"Database Design","database;sql;design"
            """);

        var dataSource = new CsvDataSource(csvFile);
        var detectionResult = modelSpaceDetector.detect(
            List.of(dataSource),
            Map.of(),
            ";"
        );

        var schema = getSchemaName();
        var request = new ImportRequest(schema, List.of(dataSource), detectionResult);
        var result = awaitImportCompletion(importData(request));

        assertThat(result.totalRowsImported()).isEqualTo(3);

        // Verify collection values are imported
        var rows = jdbcTemplate.queryForList("SELECT * FROM %s.articles ORDER BY id".formatted(schema));
        assertThat(rows).hasSize(3);

        assertThat(rows)
            .first(MAP)
            .containsEntry("title", "Java Basics")
            .containsEntry("tags", "java;programming;tutorial");

        assertThat(rows)
            .element(1, MAP)
            .containsEntry("title", "Spring Boot Guide")
            .containsEntry("tags", "spring;java;framework");

        dataSource.close();
    }

    @Test
    @DisplayName("imports reference attributes pointing to existing roots")
    void importReferenceAttributes() throws Exception {
        // First import categories (referenced entity)
        var categoriesFile = tempDir.resolve("categories.csv");
        Files.writeString(categoriesFile, """
            id,name
            1,Electronics
            2,Books
            3,Clothing
            """);

        var categoriesSource = new CsvDataSource(categoriesFile);

        // Then import products with category_id reference
        var productsFile = tempDir.resolve("products.csv");
        Files.writeString(productsFile, """
            id,name,price,category_id
            1,Laptop,999.99,1
            2,Novel,14.99,2
            3,T-Shirt,19.99,3
            """);

        var productsSource = new CsvDataSource(productsFile);

        var detectionResult = modelSpaceDetector.detect(
            List.of(categoriesSource, productsSource),
            Map.of(),
            ";"
        );

        var schema = getSchemaName();
        var request = new ImportRequest(schema, List.of(categoriesSource, productsSource), detectionResult);
        var result = awaitImportCompletion(importData(request));

        assertThat(result.totalRowsImported()).isEqualTo(6);

        // Verify categories imported
        var categoryRows = jdbcTemplate.queryForList(
            "SELECT * FROM %s.categories ORDER BY id".formatted(schema)
        );
        assertThat(categoryRows).hasSize(3);

        // Verify products with reference to categories
        var productRows = jdbcTemplate.queryForList(
            "SELECT * FROM %s.products ORDER BY id".formatted(schema)
        );
        assertThat(productRows).hasSize(3);

        assertThat(productRows)
            .first(MAP)
            .containsEntry("name", "Laptop")
            .containsEntry("category_id", 1L);

        assertThat(productRows)
            .element(1, MAP)
            .containsEntry("name", "Novel")
            .containsEntry("category_id", 2L);

        assertThat(productRows)
            .element(2, MAP)
            .containsEntry("name", "T-Shirt")
            .containsEntry("category_id", 3L);

        categoriesSource.close();
        productsSource.close();
    }

    @Test
    @DisplayName("imports mixed attribute types in same CSV")
    void importMixedAttributeTypes() throws Exception {
        // CSV combining multiple attribute types
        var csvFile = tempDir.resolve("orders.csv");
        Files.writeString(csvFile, """
            id,order_number,customer_name,shipping_street,shipping_city,shipping_zip,product_ids,status_code,status_message
            1,ORD-001,Alice,123 Main St,Springfield,12345,101;102;103,shipped,Order shipped successfully
            2,ORD-002,Bob,456 Oak Ave,Shelbyville,67890,104,pending,Awaiting payment
            3,ORD-003,Charlie,789 Elm Rd,Capital City,11111,105;106,delivered,Delivered to customer
            """);

        var dataSource = new CsvDataSource(csvFile);
        var detectionResult = modelSpaceDetector.detect(
            List.of(dataSource),
            Map.of(
                "orders", List.of(new BasicAttributeOverride("shipping_zip", new StringType()))
            ),
            ";"
        );

        var schema = getSchemaName();
        var request = new ImportRequest(schema, List.of(dataSource), detectionResult);
        var result = awaitImportCompletion(importData(request));

        assertThat(result.totalRowsImported()).isEqualTo(3);

        var rows = jdbcTemplate.queryForList("SELECT * FROM %s.orders ORDER BY id".formatted(schema));
        assertThat(rows).hasSize(3);

        // Verify all attribute types are imported
        assertThat(rows)
            .first(MAP)
            .containsEntry("order_number", "ORD-001")
            .containsEntry("customer_name", "Alice")
            // Composite: shipping address
            .containsEntry("shipping_street", "123 Main St")
            .containsEntry("shipping_city", "Springfield")
            .containsEntry("shipping_zip", "12345")
            // Collection: product_ids
            .containsEntry("product_ids", "101;102;103")
            // Composite: status
            .containsEntry("status_code", "shipped")
            .containsEntry("status_message", "Order shipped successfully");

        assertThat(rows.get(1))
            .containsEntry("product_ids", "104");

        dataSource.close();
    }

    @Test
    @DisplayName("imports null values in composite attributes")
    void importNullValuesInComposites() throws Exception {
        var csvFile = tempDir.resolve("users.csv");
        Files.writeString(csvFile, """
            id,name,profile_bio,profile_website,profile_avatar
            1,Alice,Software Engineer,https://alice.dev,alice.jpg
            2,Bob,,,
            3,Charlie,Designer,,charlie.jpg
            """);

        var dataSource = new CsvDataSource(csvFile);
        var detectionResult = modelSpaceDetector.detect(
            List.of(dataSource),
            Map.of(),
            ";"
        );

        var schema = getSchemaName();
        var request = new ImportRequest(schema, List.of(dataSource), detectionResult);
        awaitImportCompletion(importData(request));

        var rows = jdbcTemplate.queryForList("SELECT * FROM %s.users ORDER BY id".formatted(schema));
        assertThat(rows).hasSize(3);

        // First row has all composite fields
        assertThat(rows)
            .first(MAP)
            .containsEntry("name", "Alice")
            .containsEntry("profile_bio", "Software Engineer")
            .containsEntry("profile_website", "https://alice.dev")
            .containsEntry("profile_avatar", "alice.jpg");

        // Second row has all composite fields null
        assertThat(rows)
            .element(1, MAP)
            .containsEntry("name", "Bob");
        assertThat(rows)
            .element(1, MAP)
            .containsEntry("profile_bio", null);
        assertThat(rows)
            .element(1, MAP)
            .containsEntry("profile_website", null);
        assertThat(rows)
            .element(1, MAP)
            .containsEntry("profile_avatar", null);

        // Third row has partial composite fields
        assertThat(rows)
            .element(2, MAP)
            .containsEntry("name", "Charlie")
            .containsEntry("profile_bio", "Designer")
            .containsEntry("profile_avatar", "charlie.jpg");
        assertThat(rows.get(2).get("profile_website")).isNull();

        dataSource.close();
    }

    @Test
    @DisplayName("imports deeply nested composite attributes")
    void importNestedComposites() throws Exception {
        var csvFile = tempDir.resolve("locations.csv");
        Files.writeString(csvFile, """
            id,name,address_street,address_city_name,address_city_state,address_city_country
            1,Office A,123 Main St,Springfield,IL,USA
            2,Office B,456 Oak Ave,Toronto,ON,Canada
            """);

        var dataSource = new CsvDataSource(csvFile);
        var detectionResult = modelSpaceDetector.detect(
            List.of(dataSource),
            Map.of(),
            ";"
        );

        var schema = getSchemaName();
        var request = new ImportRequest(schema, List.of(dataSource), detectionResult);
        awaitImportCompletion(importData(request));

        var rows = jdbcTemplate.queryForList("SELECT * FROM %s.locations ORDER BY id".formatted(schema));
        assertThat(rows).hasSize(2);

        // Verify nested composite structure is flattened in database
        assertThat(rows)
            .first(MAP)
            .containsEntry("name", "Office A")
            .containsEntry("address_street", "123 Main St")
            .containsEntry("address_city_name", "Springfield")
            .containsEntry("address_city_state", "IL")
            .containsEntry("address_city_country", "USA");

        dataSource.close();
    }

    @Test
    @DisplayName("imports hierarchical peak_season_months without numeric parse warnings")
    void importHierarchicalPeakSeasonMonthsWithoutNumericParseWarnings() throws Exception {
        var jsonFile = tempDir.resolve("products.json");
        Files.writeString(jsonFile, """
            [
              {"id": 1, "sku": "SKU000001", "peak_season_months": [1, 12, 2]},
              {"id": 2, "sku": "SKU000002", "peak_season_months": []},
              {"id": 4, "sku": "SKU000004", "peak_season_months": [10, 6, 12, 4]}
            ]
            """);

        var dataSource = new JsonDataSource(jsonFile);
        var detectionResult = modelSpaceDetector.detect(List.of(dataSource), Map.of(), ";");

        var schema = getSchemaName();
        var request = new ImportRequest(schema, List.of(dataSource), detectionResult);
        var result = awaitImportCompletion(importData(request));
        var progress = result.progress().blockLast(Duration.ofSeconds(30));

        assertThat(progress).isNotNull();
        assertThat(warningsForColumn(progress.events(), "peak_season_months")).isEmpty();

        var rows = jdbcTemplate.queryForList(
            "SELECT id, peak_season_months FROM %s.products ORDER BY id".formatted(schema)
        );
        assertThat(rows).hasSize(3);
        assertThat(rows)
            .filteredOn(row -> ((Number) row.get("id")).longValue() == 1L)
            .singleElement()
            .extracting(row -> row.get("peak_season_months"))
            .isNotNull();
        assertThat(rows)
            .filteredOn(row -> ((Number) row.get("id")).longValue() == 4L)
            .singleElement()
            .extracting(row -> row.get("peak_season_months"))
            .isNotNull();

        dataSource.close();
    }

    private List<String> warningsForColumn(List<ImportEvent> events, String columnName) {
        return events.stream()
            .filter(ImportEvent.ChunkProcessed.class::isInstance)
            .map(ImportEvent.ChunkProcessed.class::cast)
            .flatMap(event -> event.warnings().stream())
            .filter(warning -> warning.contains(columnName))
            .toList();
    }

    @Test
    @DisplayName("imports flat peak_season_months without numeric parse warnings when collection override is numeric")
    void importFlatPeakSeasonMonthsWithNumericCollectionOverrideWithoutParseWarnings() throws Exception {
        var csvFile = tempDir.resolve("products_flat.csv");
        Files.writeString(csvFile, """
            id,peak_season_months
            1,1;12;2
            2,
            4,10;6;12;4
            """);

        var dataSource = new CsvDataSource(csvFile);
        var overrides = Map.of(
            "products_flat",
            List.of(new CollectionAttributeOverride(
                "peakSeasonMonths",
                new DataType.NumericType(19, 0),
                ";"
            ))
        );
        var detectionResult = modelSpaceDetector.detect(List.of(dataSource), overrides, ";");

        var schema = getSchemaName();
        var request = new ImportRequest(schema, List.of(dataSource), detectionResult);
        var result = awaitImportCompletion(importData(request));
        var progress = result.progress().blockLast(Duration.ofSeconds(30));

        assertThat(progress).isNotNull();
        assertThat(warningsForColumn(progress.events(), "peak_season_months")).isEmpty();

        var rows = jdbcTemplate.queryForList(
            "SELECT id, peak_season_months FROM %s.products_flat ORDER BY id".formatted(schema)
        );
        assertThat(rows).hasSize(3);
        assertThat(rows)
            .filteredOn(row -> ((Number) row.get("id")).longValue() == 1L)
            .singleElement()
            .extracting(row -> row.get("peak_season_months"))
            .isNotNull();
        assertThat(rows)
            .filteredOn(row -> ((Number) row.get("id")).longValue() == 4L)
            .singleElement()
            .extracting(row -> row.get("peak_season_months"))
            .isNotNull();

        dataSource.close();
    }
}
