package com.rorm.dataimport.pipeline;

import com.rorm.dataimport.hierarchical.JsonDataSource;
import com.rorm.dataimport.hierarchical.YamlDataSource;
import com.rorm.dataimport.source.CsvDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoadDataSourcesStepTest {

    private final LoadDataSourcesStep step = new LoadDataSourcesStep();

    @Test
    void loadsCsvFiles(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("data.csv"), "id,name\n1,Alice");

        var sources = step.loadFromDirectory(dir);

        assertThat(sources).hasSize(1);
        assertThat(sources.getFirst()).isInstanceOf(CsvDataSource.class);
    }

    @Test
    void loadsJsonFiles(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("data.json"), "[{\"id\":1}]");

        var sources = step.loadFromDirectory(dir);

        assertThat(sources).hasSize(1);
        assertThat(sources.getFirst()).isInstanceOf(JsonDataSource.class);
    }

    @Test
    void loadsYamlFiles(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("data.yaml"), "- id: 1\n  name: Alice");

        var sources = step.loadFromDirectory(dir);

        assertThat(sources).hasSize(1);
        assertThat(sources.getFirst()).isInstanceOf(YamlDataSource.class);
    }

    @Test
    void loadsYmlExtension(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("data.yml"), "- id: 1");

        var sources = step.loadFromDirectory(dir);

        assertThat(sources).hasSize(1);
        assertThat(sources.getFirst()).isInstanceOf(YamlDataSource.class);
    }

    @Test
    void loadsTsvFiles(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("data.tsv"), "id\tname\n1\tAlice");

        var sources = step.loadFromDirectory(dir);

        assertThat(sources).hasSize(1);
        assertThat(sources.getFirst()).isInstanceOf(CsvDataSource.class);
    }

    @Test
    void skipsUnsupportedFileTypes(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("readme.md"), "# Readme");
        Files.writeString(dir.resolve("data.csv"), "id,name\n1,Alice");

        var sources = step.loadFromDirectory(dir);

        assertThat(sources).hasSize(1);
        assertThat(sources.getFirst()).isInstanceOf(CsvDataSource.class);
    }

    @Test
    void loadsMultipleFiles(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("users.csv"), "id,name\n1,Alice");
        Files.writeString(dir.resolve("orders.json"), "[{\"id\":1}]");

        var sources = step.loadFromDirectory(dir);

        assertThat(sources).hasSize(2);
    }

    @Test
    void emptyDirectoryReturnsEmptyList(@TempDir Path dir) {
        var sources = step.loadFromDirectory(dir);

        assertThat(sources).isEmpty();
    }

    @Test
    void loadFromPathsCreateCorrectTypes(@TempDir Path dir) throws IOException {
        var csv = Files.writeString(dir.resolve("a.csv"), "id\n1");
        var json = Files.writeString(dir.resolve("b.json"), "[{}]");
        var yaml = Files.writeString(dir.resolve("c.yml"), "- id: 1");

        var sources = step.loadFromPaths(List.of(csv, json, yaml));

        assertThat(sources).hasSize(3);
        assertThat(sources.get(0)).isInstanceOf(CsvDataSource.class);
        assertThat(sources.get(1)).isInstanceOf(JsonDataSource.class);
        assertThat(sources.get(2)).isInstanceOf(YamlDataSource.class);
    }

    @Test
    void throwsUncheckedIOExceptionForMissingDirectory() {
        var missingDir = Path.of("/nonexistent/path/that/does/not/exist");

        assertThatThrownBy(() -> step.loadFromDirectory(missingDir))
            .isInstanceOf(UncheckedIOException.class)
            .hasMessageContaining("Failed to list files")
            .hasCauseInstanceOf(IOException.class);
    }
}
