package com.rorm.dataimport.pipeline;

import com.rorm.dataimport.hierarchical.JsonDataSource;
import com.rorm.dataimport.hierarchical.YamlDataSource;
import com.rorm.dataimport.source.CsvDataSource;
import com.rorm.dataimport.source.ImportDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

@Slf4j
@Component
@Order(1)
public class LoadDataSourcesStep implements ImportStep<ImportPipelineRequest, LoadedSources> {

    @Override
    public LoadedSources execute(ImportPipelineRequest request) {
        var dataSources = loadFromDirectory(request.uploadDir());
        log.info("Loaded {} data source(s) from {}", dataSources.size(), request.uploadDir());
        return new LoadedSources(request, dataSources);
    }

    public List<ImportDataSource> loadFromDirectory(Path directory) {
        try (Stream<Path> files = Files.list(directory)) {
            return files
                .filter(Files::isRegularFile)
                .map(LoadDataSourcesStep::createDataSource)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list files in upload directory: " + directory, e);
        }
    }

    static Optional<ImportDataSource> createDataSource(Path path) {
        var name = path.getFileName().toString().toLowerCase();

        if (name.endsWith(".csv") || name.endsWith(".tsv") || name.endsWith(".txt")) {
            return Optional.of(new CsvDataSource(path));
        } else if (name.endsWith(".json")) {
            return Optional.of(new JsonDataSource(path));
        } else if (name.endsWith(".yaml") || name.endsWith(".yml")) {
            return Optional.of(new YamlDataSource(path));
        } else {
            log.warn("Unsupported file type: {}", name);
            return Optional.empty();
        }
    }

    public List<ImportDataSource> loadFromPaths(List<Path> paths) {
        return paths.stream()
            .map(LoadDataSourcesStep::createDataSource)
            .flatMap(Optional::stream)
            .toList();
    }
}
