package com.rorm.dataimport.pipeline;

import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

class DatabaseItemWriter implements ItemWriter<Map<String, String>> {

    private final JdbcTemplate jdbcTemplate;
    private final String qualifiedTableName;
    private final List<String> dataColumns;
    private final boolean hasIdColumn;
    private final String insertSql;
    private final AtomicLong rowCounter;

    DatabaseItemWriter(
        JdbcTemplate jdbcTemplate,
        String schema,
        String tableName,
        List<String> columnNames
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.qualifiedTableName = "%s.%s".formatted(schema, tableName);
        this.hasIdColumn = columnNames.stream().anyMatch(col -> col.equalsIgnoreCase("id"));
        this.dataColumns = columnNames.stream()
            .filter(col -> !col.equalsIgnoreCase("id"))
            .toList();
        this.insertSql = buildInsertSql();
        this.rowCounter = new AtomicLong(0);
    }

    private String buildInsertSql() {
        var allColumns = Stream.concat(Stream.of("id"), dataColumns.stream()).toList();
        var placeholders = allColumns.stream().map(_ -> "?").toList();
        return "INSERT INTO %s (%s) VALUES (%s)".formatted(
            qualifiedTableName,
            String.join(", ", allColumns),
            String.join(", ", placeholders)
        );
    }

    @Override
    public void write(Chunk<? extends Map<String, String>> chunk) {
        for (var row : chunk) {
            var id = determineRowId(row);
            var values = buildRowValues(row, id);
            jdbcTemplate.update(insertSql, values);
        }
    }

    private long determineRowId(Map<String, String> row) {
        var nextId = rowCounter.incrementAndGet();
        if (!hasIdColumn || !row.containsKey("id")) {
            return nextId;
        }

        try {
            return Long.parseLong(row.get("id"));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid ID format in row: " + row, e);
        }
    }

    private Object[] buildRowValues(Map<String, String> row, long id) {
        return Stream.concat(
            Stream.of(id),
            dataColumns.stream().map(col -> {
                var value = row.get(col);
                return (value == null || value.isEmpty()) ? null : value;
            })
        ).toArray();
    }
}
