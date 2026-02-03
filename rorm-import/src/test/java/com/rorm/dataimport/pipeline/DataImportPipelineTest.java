package com.rorm.dataimport.pipeline;

import com.rorm.dataimport.source.CsvDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class DataImportPipelineTest extends AbstractImportTest {

    @TempDir
    Path tempDir;
    @Autowired
    private MetamodelConverter metamodelConverter;

    @Test
    @DisplayName("imports single CSV file with basic columns")
    void importSingleFile() throws Exception {
        var csvFile = tempDir.resolve("users.csv");
        Files.writeString(csvFile, """
            id,name,email,age
            1,Alice,alice@example.com,30
            2,Bob,bob@example.com,25
            3,Charlie,charlie@example.com,35
            """);

        var dataSource = new CsvDataSource(csvFile);
        var detectionResult = modelSpaceDetector.detect(
            List.of(dataSource),
            Map.of(),
            ";"
        );

        var schema = getSchemaName();
        var request = new ImportRequest(schema, List.of(dataSource), detectionResult);
        var result = dataImportPipeline.importData(request);

        assertThat(result.targetSchema()).isEqualTo(schema);
        assertThat(result.totalRowsImported()).isEqualTo(3);
        var modelSpace = metamodelConverter.convertToModelSpace(detectionResult);
        assertThat(result.modelSpace()).isEqualTo(modelSpace);

        // Verify data in database
        var rows = jdbcTemplate.queryForList("SELECT * FROM %s.users ORDER BY id".formatted(schema));
        assertThat(rows).hasSize(3);

        assertThat(rows.get(0))
            .containsEntry("name", "Alice")
            .containsEntry("email", "alice@example.com")
            .containsEntry("age", 30L);

        assertThat(rows.get(1))
            .containsEntry("name", "Bob")
            .containsEntry("email", "bob@example.com")
            .containsEntry("age", 25L);

        assertThat(rows.get(2))
            .containsEntry("name", "Charlie")
            .containsEntry("email", "charlie@example.com")
            .containsEntry("age", 35L);

        dataSource.close();
    }

    @Test
    @DisplayName("imports CSV with explicit id column")
    void importWithExplicitIds() throws Exception {
        var csvFile = tempDir.resolve("products.csv");
        Files.writeString(csvFile, """
            id,name,price
            100,Laptop,999.99
            200,Mouse,19.99
            """);

        var dataSource = new CsvDataSource(csvFile);
        var detectionResult = modelSpaceDetector.detect(
            List.of(dataSource),
            Map.of(),
            ";"
        );

        var schema = getSchemaName();
        var request = new ImportRequest(schema, List.of(dataSource), detectionResult);
        var result = dataImportPipeline.importData(request);

        assertThat(result.totalRowsImported()).isEqualTo(2);

        var rows = jdbcTemplate.queryForList("SELECT * FROM %s.products ORDER BY id".formatted(schema));
        assertThat(rows).hasSize(2);

        assertThat(rows.get(0))
            .containsEntry("id", 100L)
            .containsEntry("name", "Laptop")
            .containsEntry("price", new java.math.BigDecimal("999.99"));

        assertThat(rows.get(1))
            .containsEntry("id", 200L)
            .containsEntry("name", "Mouse")
            .containsEntry("price", new java.math.BigDecimal("19.99"));

        dataSource.close();
    }

    @Test
    @DisplayName("generates sequential ids when no id column present")
    void importGeneratesSequentialIds() throws Exception {
        var csvFile = tempDir.resolve("categories.csv");
        Files.writeString(csvFile, """
            id,name,description
            1,Electronics,Electronic devices
            2,Books,Physical and digital books
            3,Clothing,Apparel and accessories
            """);

        var dataSource = new CsvDataSource(csvFile);
        var detectionResult = modelSpaceDetector.detect(
            List.of(dataSource),
            Map.of(),
            ";"
        );

        var schema = getSchemaName();
        var request = new ImportRequest(schema, List.of(dataSource), detectionResult);
        dataImportPipeline.importData(request);

        var ids = jdbcTemplate.queryForList(
            "SELECT id FROM %s.categories ORDER BY id".formatted(schema),
            Long.class
        );

        assertThat(ids).containsExactly(1L, 2L, 3L);

        dataSource.close();
    }

    @Test
    @DisplayName("imports empty CSV with headers only")
    void importEmptyFile() throws Exception {
        var csvFile = tempDir.resolve("empty.csv");
        Files.writeString(csvFile, """
            name,email
            """);

        var dataSource = new CsvDataSource(csvFile);
        var detectionResult = modelSpaceDetector.detect(
            List.of(dataSource),
            Map.of(),
            ";"
        );

        var schema = getSchemaName();
        var request = new ImportRequest(schema, List.of(dataSource), detectionResult);
        var result = dataImportPipeline.importData(request);

        assertThat(result.totalRowsImported()).isEqualTo(0);

        var count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM %s.empty".formatted(schema),
            Long.class
        );
        assertThat(count).isEqualTo(0);

        dataSource.close();
    }

    @Test
    @DisplayName("creates schema if it doesn't exist")
    void createSchemaIfNotExists() throws Exception {
        var csvFile = tempDir.resolve("test.csv");
        Files.writeString(csvFile, """
            value
            test
            """);

        var dataSource = new CsvDataSource(csvFile);
        var detectionResult = modelSpaceDetector.detect(
            List.of(dataSource),
            Map.of(),
            ";"
        );

        var newSchema = "import_test_" + System.currentTimeMillis();
        var request = new ImportRequest(newSchema, List.of(dataSource), detectionResult);

        try {
            dataImportPipeline.importData(request);

            var schemaExists = jdbcTemplate.queryForObject(
                "select exists(select 1 from pg_namespace where nspname = ?)",
                Boolean.class,
                newSchema
            );
            assertThat(schemaExists).isTrue();

        } finally {
            jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + newSchema + " CASCADE");
            dataSource.close();
        }
    }

    @Test
    @DisplayName("handles null values in CSV")
    void importWithNullValues() throws Exception {
        var csvFile = tempDir.resolve("users.csv");
        Files.writeString(csvFile, """
            id,name,email,phone
            1,Alice,alice@example.com,
            2,Bob,,555-1234
            """);

        var dataSource = new CsvDataSource(csvFile);
        var detectionResult = modelSpaceDetector.detect(
            List.of(dataSource),
            Map.of(),
            ";"
        );

        var schema = getSchemaName();
        var request = new ImportRequest(schema, List.of(dataSource), detectionResult);
        dataImportPipeline.importData(request);

        var rows = jdbcTemplate.queryForList("SELECT * FROM %s.users ORDER BY id".formatted(schema));
        assertThat(rows).hasSize(2);

        var row1 = rows.getFirst();
        assertThat(row1.get("name")).isEqualTo("Alice");
        assertThat(row1.get("email")).isEqualTo("alice@example.com");
        assertThat(row1.get("phone")).isNull();

        var row2 = rows.get(1);
        assertThat(row2.get("name")).isEqualTo("Bob");
        assertThat(row2.get("email")).isNull();
        assertThat(row2.get("phone")).isEqualTo("555-1234");

        dataSource.close();
    }
}
