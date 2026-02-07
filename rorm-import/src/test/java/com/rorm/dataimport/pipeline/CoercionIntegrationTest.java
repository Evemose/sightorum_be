package com.rorm.dataimport.pipeline;

import com.rorm.dataimport.attribute.DetectedAttribute.Basic;
import com.rorm.dataimport.pipeline.ImportRequest.CoercionBuilder;
import com.rorm.dataimport.pipeline.SchemaDetector.DetectedIdColumn;
import com.rorm.dataimport.pipeline.SchemaDetector.DetectedRoot;
import com.rorm.dataimport.source.CsvDataSource;
import com.rorm.metamodel.DataType.NumericType;
import com.rorm.metamodel.DataType.StringType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SuppressWarnings("SqlSourceToSinkFlow")
@DisplayName("Coercion Integration Tests")
class CoercionIntegrationTest extends AbstractImportTest {

    private Path tempDir;

    @BeforeEach
    @Override
    protected void setupTestSchema() {
        super.setupTestSchema();
        try {
            tempDir = Files.createTempDirectory("coercion-test");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @AfterEach
    void cleanupTempDir() throws IOException {
        if (tempDir != null && Files.exists(tempDir)) {
            try (var stream = Files.walk(tempDir)) {
                stream.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException _) {
                            // ignore
                        }
                    });
            }
        }
    }

    @Test
    @DisplayName("Skip strategy should set NULL for invalid values")
    void skipStrategyShouldSetNull() throws Exception {
        var csvPath = tempDir.resolve("users.csv");
        Files.writeString(csvPath, """
            id,name,age
            1,Alice,25
            2,Bob,invalid
            3,Charlie,30
            """);

        var dataSource = new CsvDataSource(csvPath);

        // Override type detection to force age to be numeric despite invalid value
        var overrides = Map.of(
            "users", List.of(
                new com.rorm.dataimport.override.SchemaOverride.BasicAttributeOverride(
                    "age",
                    new NumericType(10, 0)
                )
            )
        );

        var detectedSchema = modelSpaceDetector.detect(List.of(dataSource), overrides, ",");

        var request = ImportRequest.forSchema(testSchema, detectedSchema)
            .withCoercionForPath("users", "age", CoercionBuilder::skip)
            .importFromSources(List.of(dataSource));

        dataImportPipeline.importData(request);

        var ages = jdbcTemplate.queryForList(
            "SELECT age FROM " + testSchema + ".users ORDER BY id",
            Integer.class
        );

        assertThat(ages).containsExactly(25, null, 30);
    }

    @Test
    @DisplayName("UseDefault with standard defaults should use type-appropriate values")
    void useDefaultsStrategyShouldUseTypeDefaults() throws Exception {
        var csvPath = tempDir.resolve("users.csv");
        Files.writeString(csvPath, """
            id,name,age,active
            1,Alice,25,true
            2,Bob,invalid,invalid
            3,Charlie,30,false
            """);

        var dataSource = new CsvDataSource(csvPath);

        // Override type detection to force types despite invalid values
        var overrides = Map.of(
            "users", List.of(
                new com.rorm.dataimport.override.SchemaOverride.BasicAttributeOverride(
                    "age",
                    new NumericType(10, 0)
                ),
                new com.rorm.dataimport.override.SchemaOverride.BasicAttributeOverride(
                    "active",
                    new com.rorm.metamodel.DataType.BooleanType()
                )
            )
        );

        var detectedSchema = modelSpaceDetector.detect(List.of(dataSource), overrides, ",");

        var request = ImportRequest.forSchema(testSchema, detectedSchema)
            .withCoercionForPath("users", "age", CoercionBuilder::useDefaults)
            .withCoercionForPath("users", "active", CoercionBuilder::useDefaults)
            .importFromSources(List.of(dataSource));

        dataImportPipeline.importData(request);

        var ages = jdbcTemplate.queryForList(
            "SELECT age FROM " + testSchema + ".users ORDER BY id",
            Integer.class
        );
        var actives = jdbcTemplate.queryForList(
            "SELECT active FROM " + testSchema + ".users ORDER BY id",
            Boolean.class
        );

        assertThat(ages).containsExactly(25, 0, 30);
        assertThat(actives).containsExactly(true, false, false);
    }

    @Test
    @DisplayName("UseDefault with literal value should use specified value")
    void useDefaultWithLiteralShouldUseSpecifiedValue() throws Exception {
        var csvPath = tempDir.resolve("users.csv");
        Files.writeString(csvPath, """
            id,name,age
            1,Alice,25
            2,Bob,invalid
            3,Charlie,30
            """);

        var dataSource = new CsvDataSource(csvPath);

        // Override type detection
        var overrides = Map.of(
            "users", List.of(
                new com.rorm.dataimport.override.SchemaOverride.BasicAttributeOverride(
                    "age",
                    new NumericType(10, 0)
                )
            )
        );

        var detectedSchema = modelSpaceDetector.detect(List.of(dataSource), overrides, ",");

        var request = ImportRequest.forSchema(testSchema, detectedSchema)
            .withCoercionForPath("users", "age", c -> c.useDefault(18))
            .importFromSources(List.of(dataSource));

        dataImportPipeline.importData(request);

        var ages = jdbcTemplate.queryForList(
            "SELECT age FROM " + testSchema + ".users ORDER BY id",
            Integer.class
        );

        assertThat(ages).containsExactly(25, 18, 30);
    }

    @Test
    @DisplayName("UseDefault with incompatible literal type should throw")
    void useDefaultWithIncompatibleTypeShouldThrow() throws Exception {
        var csvPath = tempDir.resolve("users.csv");
        Files.writeString(csvPath, """
            id,name,age
            1,Alice,25
            """);

        var dataSource = new CsvDataSource(csvPath);
        var detectedSchema = modelSpaceDetector.detect(List.of(dataSource), Map.of(), ",");

        assertThatThrownBy(() ->
            ImportRequest.forSchema(testSchema, detectedSchema)
                .withCoercionForPath("users", "age", c -> c.useDefault("not a number"))
        ).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("incompatible");
    }

    @Test
    @DisplayName("ThrowOnInvalid strategy should throw on invalid values")
    void throwOnInvalidShouldThrowException() throws Exception {
        var csvPath = tempDir.resolve("users.csv");
        Files.writeString(csvPath, """
            id,name,age
            1,Alice,25
            2,Bob,invalid
            """);

        var dataSource = new CsvDataSource(csvPath);

        // Override type detection
        var overrides = Map.of(
            "users", List.of(
                new com.rorm.dataimport.override.SchemaOverride.BasicAttributeOverride(
                    "age",
                    new NumericType(10, 0)
                )
            )
        );

        var detectedSchema = modelSpaceDetector.detect(List.of(dataSource), overrides, ",");

        var request = ImportRequest.forSchema(testSchema, detectedSchema)
            .withCoercionForPath("users", "age", CoercionBuilder::throwOnInvalid)
            .importFromSources(List.of(dataSource));

        assertThatThrownBy(() -> dataImportPipeline.importData(request))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Numeric ROUND strategy should round overflowing values")
    void numericRoundShouldRoundValues() throws Exception {
        var csvPath = tempDir.resolve("users.csv");
        Files.writeString(csvPath, """
            id,name,score
            1,Alice,0.123456789012345678
            2,Bob,10.987654321098765432
            3,Charlie,0.5
            """);

        var dataSource = new CsvDataSource(csvPath);
        var detectedSchema = modelSpaceDetector.detect(List.of(dataSource), Map.of(), ",");

        var request = ImportRequest.forSchema(testSchema, detectedSchema)
            .withCoercionForPath("users", "score", c -> c.asNumeric().round())
            .importFromSources(List.of(dataSource));

        dataImportPipeline.importData(request);

        var scores = jdbcTemplate.queryForList(
            "SELECT score FROM " + testSchema + ".users ORDER BY id",
            BigDecimal.class
        );

        assertThat(scores).hasSize(3);
        assertThat(scores.get(0)).isNotNull();
        assertThat(scores.get(1)).isNotNull();
        assertThat(scores.get(2)).isEqualByComparingTo(new BigDecimal("0.5"));
    }

    @Test
    @DisplayName("Numeric CLAMP strategy should clamp to max value")
    void numericClampShouldClampToMax() throws Exception {
        var csvPath = tempDir.resolve("users.csv");
        Files.writeString(csvPath, """
            id,name,score
            1,Alice,0.5
            2,Bob,999.99
            3,Charlie,0.1
            """);

        var dataSource = new CsvDataSource(csvPath);
        var detectedSchema = modelSpaceDetector.detect(List.of(dataSource), Map.of(), ",");

        var request = ImportRequest.forSchema(testSchema, detectedSchema)
            .withCoercionForPath("users", "score", c -> c.asNumeric().clamp())
            .importFromSources(List.of(dataSource));

        dataImportPipeline.importData(request);

        var scores = jdbcTemplate.queryForList(
            "SELECT score FROM " + testSchema + ".users ORDER BY id",
            BigDecimal.class
        );

        assertThat(scores).hasSize(3);
        assertThat(scores.get(0)).isEqualByComparingTo(new BigDecimal("0.5"));
        assertThat(scores.get(2)).isEqualByComparingTo(new BigDecimal("0.1"));
    }

    @Test
    @DisplayName("Numeric TRUNCATE strategy should truncate decimals")
    void numericTruncateShouldTruncate() throws Exception {
        var csvPath = tempDir.resolve("users.csv");
        Files.writeString(csvPath, """
            id,name,score
            1,Alice,0.123456789
            2,Bob,0.987654321
            """);

        var dataSource = new CsvDataSource(csvPath);
        var detectedSchema = modelSpaceDetector.detect(List.of(dataSource), Map.of(), ",");

        var request = ImportRequest.forSchema(testSchema, detectedSchema)
            .withCoercionForPath("users", "score", c -> c.asNumeric().truncate())
            .importFromSources(List.of(dataSource));

        dataImportPipeline.importData(request);

        var scores = jdbcTemplate.queryForList(
            "SELECT score FROM " + testSchema + ".users ORDER BY id",
            BigDecimal.class
        );

        assertThat(scores).hasSize(2);
        assertThat(scores.get(0)).isNotNull();
        assertThat(scores.get(1)).isNotNull();
    }

    @Test
    @DisplayName("Numeric NULL_ON_OVERFLOW strategy should set NULL for overflowing values")
    void numericNullOnOverflowShouldSetNull() throws Exception {
        var csvPath = tempDir.resolve("users.csv");
        Files.writeString(csvPath, """
            id,name,score
            1,Alice,0.5
            2,Bob,999.99
            3,Charlie,0.1
            """);

        var dataSource = new CsvDataSource(csvPath);
        var detectedSchema = new DetectedSchema(Map.of(
            "users", new DetectedRoot(
                "users",
                "users",
                Map.of(
                    "id", new Basic("id", new SourceMapping("users", "id"), new NumericType(10, 0)),
                    "name", new Basic("name", new SourceMapping("users", "name"), new StringType()),
                    // force overflow for 999.99
                    "score", new Basic("score", new SourceMapping("users", "score"), new NumericType(3, 1))
                ),
                new DetectedIdColumn("id", "id", new NumericType(10, 0))
            )
        ));

        var request = ImportRequest.forSchema(testSchema, detectedSchema)
            .withCoercionForPath("users", "score", CoercionBuilder::nullOnInvalid)
            .importFromSources(java.util.List.of(dataSource));

        dataImportPipeline.importData(request);

        var scores = jdbcTemplate.queryForList(
            "SELECT score FROM " + testSchema + ".users ORDER BY id",
            BigDecimal.class
        );

        assertThat(scores).containsExactly(
            new BigDecimal("0.5"),
            null,
            new BigDecimal("0.1")
        );
    }

    @Test
    @DisplayName("Numeric NORMALIZE_PERCENTAGE should clamp to 0-1 range")
    void numericNormalizePercentageShouldClampRange() throws Exception {
        var csvPath = tempDir.resolve("users.csv");
        Files.writeString(csvPath, """
            id,name,score
            1,Alice,0.5
            2,Bob,1.5
            3,Charlie,-0.5
            """);

        var dataSource = new CsvDataSource(csvPath);
        var detectedSchema = modelSpaceDetector.detect(List.of(dataSource), Map.of(), ",");

        var request = ImportRequest.forSchema(testSchema, detectedSchema)
            .withCoercionForPath("users", "score", c -> c.asNumeric().clampPercentage())
            .importFromSources(List.of(dataSource));

        dataImportPipeline.importData(request);

        var scores = jdbcTemplate.queryForList(
            "SELECT score FROM " + testSchema + ".users ORDER BY id",
            Double.class
        );

        assertThat(scores).containsExactly(0.5, 1.0, 0.0);
    }

    @Test
    @DisplayName("AsNumeric on non-numeric attribute should throw")
    void asNumericOnNonNumericShouldThrow() throws Exception {
        var csvPath = tempDir.resolve("users.csv");
        Files.writeString(csvPath, """
            id,name
            1,Alice
            """);

        var dataSource = new CsvDataSource(csvPath);
        var detectedSchema = modelSpaceDetector.detect(List.of(dataSource), Map.of(), ",");

        assertThatThrownBy(() ->
            ImportRequest.forSchema(testSchema, detectedSchema)
                .withCoercionForPath("users", "name", c -> c.asNumeric().round())
        ).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not numeric");
    }

    @Test
    @DisplayName("ForwardFill should propagate last valid value forward")
    void forwardFillShouldPropagateForward() throws Exception {
        var csvPath = tempDir.resolve("users.csv");
        Files.writeString(csvPath, """
            id,name,score
            1,Alice,10.0
            2,Bob,invalid
            3,Charlie,invalid
            4,Dave,20.0
            5,Eve,invalid
            """);

        var dataSource = new CsvDataSource(csvPath);
        var detectedSchema = modelSpaceDetector.detect(List.of(dataSource), Map.of(), ",");

        var request = ImportRequest.forSchema(testSchema, detectedSchema)
            .withCoercionForPath("users", "score", CoercionBuilder::forwardFill)
            .importFromSources(List.of(dataSource));

        dataImportPipeline.importData(request);

        var scores = jdbcTemplate.queryForList(
            "SELECT score FROM " + testSchema + ".users ORDER BY id",
            Double.class
        );

        assertThat(scores).containsExactly(10.0, 10.0, 10.0, 20.0, 20.0);
    }

    @Test
    @DisplayName("BackwardFill should propagate next valid value backward")
    void backwardFillShouldPropagateBackward() throws Exception {
        var csvPath = tempDir.resolve("users.csv");
        Files.writeString(csvPath, """
            id,name,score
            1,Alice,invalid
            2,Bob,invalid
            3,Charlie,15.0
            4,Dave,invalid
            5,Eve,25.0
            """);

        var dataSource = new CsvDataSource(csvPath);
        var detectedSchema = modelSpaceDetector.detect(List.of(dataSource), Map.of(), ",");

        var request = ImportRequest.forSchema(testSchema, detectedSchema)
            .withCoercionForPath("users", "score", CoercionBuilder::backwardFill)
            .importFromSources(List.of(dataSource));

        dataImportPipeline.importData(request);

        var scores = jdbcTemplate.queryForList(
            "SELECT score FROM " + testSchema + ".users ORDER BY id",
            Double.class
        );

        assertThat(scores).containsExactly(15.0, 15.0, 15.0, 25.0, 25.0);
    }

    @Test
    @DisplayName("UseMean should fill with average for numeric columns")
    void useMeanShouldFillWithAverage() throws Exception {
        var csvPath = tempDir.resolve("users.csv");
        Files.writeString(csvPath, """
            id,name,score
            1,Alice,10.0
            2,Bob,invalid
            3,Charlie,20.0
            4,Dave,invalid
            5,Eve,30.0
            """);

        var dataSource = new CsvDataSource(csvPath);
        var detectedSchema = modelSpaceDetector.detect(List.of(dataSource), Map.of(), ",");

        var request = ImportRequest.forSchema(testSchema, detectedSchema)
            .withCoercionForPath("users", "score", CoercionBuilder::useMean)
            .importFromSources(List.of(dataSource));

        dataImportPipeline.importData(request);

        var scores = jdbcTemplate.queryForList(
            "SELECT score FROM " + testSchema + ".users ORDER BY id",
            Double.class
        );

        // Average of 10, 20, 30 = 20
        assertThat(scores).containsExactly(10.0, 20.0, 20.0, 20.0, 30.0);
    }

    @Test
    @DisplayName("UseMedian should fill with median for numeric columns")
    void useMedianShouldFillWithMedian() throws Exception {
        var csvPath = tempDir.resolve("users.csv");
        Files.writeString(csvPath, """
            id,name,score
            1,Alice,10.0
            2,Bob,invalid
            3,Charlie,15.0
            4,Dave,invalid
            5,Eve,30.0
            """);

        var dataSource = new CsvDataSource(csvPath);
        var detectedSchema = modelSpaceDetector.detect(List.of(dataSource), Map.of(), ",");

        var request = ImportRequest.forSchema(testSchema, detectedSchema)
            .withCoercionForPath("users", "score", CoercionBuilder::useMedian)
            .importFromSources(List.of(dataSource));

        dataImportPipeline.importData(request);

        var scores = jdbcTemplate.queryForList(
            "SELECT score FROM " + testSchema + ".users ORDER BY id",
            Double.class
        );

        // Median of 10, 15, 30 = 15
        assertThat(scores).containsExactly(10.0, 15.0, 15.0, 15.0, 30.0);
    }

    @Test
    @DisplayName("UseMode should fill with most frequent value")
    void useModeShouldFillWithMostFrequent() throws Exception {
        var csvPath = tempDir.resolve("users.csv");
        Files.writeString(csvPath, """
            id,name,status
            1,Alice,active
            2,Bob,invalid
            3,Charlie,active
            4,Dave,inactive
            5,Eve,invalid
            6,Frank,active
            """);

        var dataSource = new CsvDataSource(csvPath);
        var detectedSchema = modelSpaceDetector.detect(List.of(dataSource), Map.of(), ",");

        var request = ImportRequest.forSchema(testSchema, detectedSchema)
            .withCoercionForPath("users", "status", CoercionBuilder::useMode)
            .importFromSources(List.of(dataSource));

        dataImportPipeline.importData(request);

        var statuses = jdbcTemplate.queryForList(
            "SELECT status FROM " + testSchema + ".users ORDER BY id",
            String.class
        );

        // Mode is "active" (appears 3 times)
        assertThat(statuses).containsExactly("active", "active", "active", "inactive", "active", "active");
    }
}
