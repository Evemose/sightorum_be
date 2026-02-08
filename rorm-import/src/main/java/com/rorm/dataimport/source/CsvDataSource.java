package com.rorm.dataimport.source;

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import com.rorm.dataimport.naming.NamingStyle;
import com.rorm.dataimport.naming.NamingStyleDetector;
import lombok.SneakyThrows;
import org.jspecify.annotations.Nullable;

import java.io.BufferedInputStream;
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
    private final NamingStyle namingStyle;
    @Nullable
    private MappingIterator<Map<String, String>> iterator;
    @Nullable
    private InputStream inputStream;

    public CsvDataSource(Path filePath) {
        this.filePath = filePath;
        this.rootName = extractRootName(filePath);

        // Detect naming style and normalize column names once, at initialization
        var detector = new NamingStyleDetector();
        var originalColumns = readOriginalColumnNames();
        this.namingStyle = detector.detect(originalColumns);
        this.columnNames = originalColumns.stream()
            .map(namingStyle::forceAdjust)
            .toList();
    }

    private String extractRootName(Path filePath) {
        var fileName = filePath.getFileName().toString();
        var dotIndex = fileName.lastIndexOf('.');
        return dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
    }

    /**
     * Reads the original column names from the CSV header without any transformations.
     */
    @SneakyThrows
    private List<String> readOriginalColumnNames() {
        var mapper = new CsvMapper();
        var schema = CsvSchema.emptySchema().withHeader();

        try (var is = Files.newInputStream(filePath)) {
            var it = mapper.readerFor(Map.class)
                .with(schema)
                .<Map<String, String>>readValues(is);

            var parser = it.getParser();
            var readSchema = parser.getSchema();
            if (readSchema instanceof CsvSchema csvSchema && csvSchema.size() > 0) {
                return StreamSupport.stream(csvSchema.spliterator(), false)
                    .map(CsvSchema.Column::getName)
                    .toList();
            }

            // Fallback: try to read first row
            return it.hasNext()
                ? new ArrayList<>(it.next().keySet())
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
    public long countRows() {
        try (var lines = Files.lines(filePath)) {
            return Math.max(0, lines.count() - 1); // minus header row
        } catch (IOException e) {
            return -1;
        }
    }

    @Override
    public Stream<Map<String, Object>> stream() {
        try {
            var mapper = new CsvMapper();
            var schema = CsvSchema.emptySchema().withHeader();

            inputStream = new BufferedInputStream(Files.newInputStream(filePath));
            iterator = mapper.readerFor(Map.class).with(schema).readValues(inputStream);

            return StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED),
                false
                )
                .map(this::transformRowKeys)
                .onClose(this::close);

        } catch (IOException e) {
            throw new RuntimeException("Failed to create CSV stream", e);
        }
    }

    /**
     * Transforms row keys from original CSV column names to normalized names.
     * This ensures data keys match the column names returned by getColumnNames().
     * Empty strings are converted to null.
     */
    @SuppressWarnings("ConstantValue")
    private Map<String, Object> transformRowKeys(Map<String, String> row) {
        var transformed = new LinkedHashMap<String, Object>();
        for (var entry : row.entrySet()) {
            var normalizedKey = namingStyle.forceAdjust(entry.getKey());
            var value = entry.getValue();
            transformed.put(normalizedKey, (value == null || value.isEmpty()) ? null : value);
        }
        return transformed;
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

}
