package com.rorm.dataimport.pipeline;

import com.rorm.dataimport.override.SchemaOverride;
import com.rorm.dataimport.pipeline.profile.SchemaAnalyzer;
import com.rorm.dataimport.pipeline.profile.SchemaProfile.SummaryFlag;
import com.rorm.dataimport.source.CsvDataSource;
import com.rorm.engine.TypeCategory;
import com.rorm.metamodel.DataType.BooleanType;
import com.rorm.metamodel.DataType.DateType;
import com.rorm.metamodel.DataType.NumericType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SchemaAnalyzer profiling")
class SchemaAnalyzerIntegrationTest extends AbstractImportTest {

    @Autowired
    private SchemaAnalyzer schemaAnalyzer;

    @TempDir
    private Path tempDir;

    @Test
    @DisplayName("profiles an imported table with row count, flags and per-attribute statistics")
    void profilesImportedTable() throws Exception {
        var csvPath = tempDir.resolve("users.csv");
        Files.writeString(csvPath, """
            id,age,active,status,joined
            1,25,true,gold,2024-01-15
            2,30,false,silver,2024-02-20
            3,35,true,gold,2024-03-10
            """);
        var dataSource = new CsvDataSource(csvPath);
        var overrides = Map.of("users", List.of(
            new SchemaOverride.BasicAttributeOverride("age", new NumericType(10, 0)),
            new SchemaOverride.BasicAttributeOverride("active", new BooleanType()),
            new SchemaOverride.BasicAttributeOverride("joined", new DateType())));
        var detectedSchema = modelSpaceDetector.detect(List.of(dataSource), overrides, ",");
        var request = ImportRequest.forSchema(testSchema, detectedSchema).importFromSources(List.of(dataSource));

        var result = awaitImportCompletion(importData(request));
        var profile = schemaAnalyzer.analyze(testSchema, result.modelSpace());

        var users = profile.findEntity("users").orElseThrow();
        assertThat(users.rowCount()).isEqualTo(3);
        assertThat(users.flags()).contains(
            SummaryFlag.SMALL_DATASET, SummaryFlag.HAS_NUMERIC, SummaryFlag.HAS_BOOLEAN, SummaryFlag.HAS_TEMPORAL);

        var age = users.findAttribute("age").orElseThrow();
        assertThat(age.category()).isEqualTo(TypeCategory.NUMERIC);
        assertThat(age.statistics().totalCount()).isEqualTo(3);
        assertThat(users.findAttribute("active").orElseThrow().category()).isEqualTo(TypeCategory.BOOLEAN);
        assertThat(users.findAttribute("joined").orElseThrow().category()).isEqualTo(TypeCategory.TEMPORAL);
        assertThat(users.findAttribute("status")).isPresent();
    }
}
