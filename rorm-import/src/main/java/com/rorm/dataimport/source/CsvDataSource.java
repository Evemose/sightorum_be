package com.rorm.dataimport.source;

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class CsvDataSource implements ImportDataSource {

    private final String rootName;
    private final Path filePath;
    private final List<String> columnNames;
    @Nullable
    private MappingIterator<Map<String, String>> iterator;
    @Nullable
    private InputStream inputStream;

    public CsvDataSource(Path filePath) throws IOException {
        this.filePath = filePath;
        this.rootName = extractRootName(filePath);
        this.columnNames = extractColumnNames();
    }

    private String extractRootName(Path filePath) {
        var fileName = filePath.getFileName().toString();
        var dotIndex = fileName.lastIndexOf('.');
        return dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
    }

    private List<String> extractColumnNames() throws IOException {
        var mapper = new CsvMapper();
        var schema = CsvSchema.emptySchema().withHeader();

        try (var is = Files.newInputStream(filePath)) {
            var it = mapper.readerFor(Map.class)
                .with(schema)
                .<Map<String, String>>readValues(is);

            // Get schema from parser after reading headers
            var parser = it.getParser();
            var readSchema = parser.getSchema();
            if (readSchema instanceof CsvSchema csvSchema && csvSchema.size() > 0) {
                return java.util.stream.StreamSupport.stream(
                        csvSchema.spliterator(), false)
                    .map(CsvSchema.Column::getName)
                    .toList();
            }

            // Fallback: try to read first row
            return it.hasNext()
                ? it.next().keySet().stream().toList()
                : List.of();
        }
    }

    @Override
    public String getRootName() {
        return rootName;
    }

    @Override
    public List<String> getColumnNames() {
        return columnNames;
    }

    @Override
    public Stream<Map<String, String>> stream() {
        try {
            var mapper = new CsvMapper();
            var schema = CsvSchema.emptySchema().withHeader();

            inputStream = Files.newInputStream(filePath);
            iterator = mapper.readerFor(Map.class)
                .with(schema)
                .readValues(inputStream);

            return StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED),
                false
            ).onClose(this::close);

        } catch (IOException e) {
            throw new RuntimeException("Failed to create CSV stream", e);
        }
    }

    @Override
    public void close() {
        closeQuietly(iterator, "iterator");
        closeQuietly(inputStream, "input stream");
    }

    private <T extends AutoCloseable> void closeQuietly(@Nullable T resource, String resourceName) {
        Optional.ofNullable(resource).ifPresent(r -> {
            try {
                r.close();
            } catch (Exception e) {
                throw new RuntimeException("Failed to close " + resourceName, e);
            }
        });
    }

    @Override
    public Stream<Map<String, String>> peekStream(int n) {
        try (var is = Files.newInputStream(filePath)) {
            var mapper = new CsvMapper();
            var schema = CsvSchema.emptySchema().withHeader();

            var it = mapper.readerFor(Map.class)
                .with(schema)
                .<Map<String, String>>readValues(is);

            return StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(it, Spliterator.ORDERED),
                false
            ).limit(n);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create peek CSV stream", e);
        }
    }
}
