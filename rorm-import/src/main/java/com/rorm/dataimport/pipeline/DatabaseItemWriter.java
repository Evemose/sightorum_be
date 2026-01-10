package com.rorm.dataimport.pipeline;

import com.rorm.dataimport.type.TypeParser;
import com.rorm.metamodel.BasicAttribute;
import com.rorm.metamodel.DataType;
import com.rorm.metamodel.IdDescriptor;
import com.rorm.metamodel.ReferenceAttribute.InverseRootTableColumn;
import com.rorm.metamodel.SingularReferenceAttribute;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

class DatabaseItemWriter implements ItemWriter<Map<String, String>> {

    private final JdbcTemplate jdbcTemplate;
    private final String qualifiedTableName;
    private final List<String> dataColumns;
    private final Map<String, DataType> columnTypes;
    private final IdDescriptor idDescriptor;
    private final String insertSql;
    private final AtomicLong rowCounter;

    DatabaseItemWriter(
        JdbcTemplate jdbcTemplate,
        String schema,
        com.rorm.metamodel.Root root,
        IdDescriptor idDescriptor
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.qualifiedTableName = "%s.%s".formatted(schema, root.primaryTableName());
        this.idDescriptor = idDescriptor;

        var columns = new ArrayList<String>();
        var types = new HashMap<String, DataType>();

        for (var attr : root.attributes()) {
            if (attr instanceof BasicAttribute basic) {
                var colName = basic.location().column();
                columns.add(colName);
                types.put(colName, basic.dataType());
            } else if (attr instanceof SingularReferenceAttribute(
                _, var targetRoot, InverseRootTableColumn(var colName)
            )) {
                columns.add(colName);
                types.put(colName, targetRoot.idDescriptor().dataType());
            }
        }

        this.columnTypes = types;
        this.dataColumns = columns.stream()
            .filter(col -> !col.equalsIgnoreCase(idDescriptor.columnName()))
            .toList();
        this.insertSql = buildInsertSql();
        this.rowCounter = new AtomicLong(0);
    }

    private String buildInsertSql() {
        var allColumns = Stream.concat(Stream.of(idDescriptor.columnName()), dataColumns.stream()).toList();
        var placeholders = allColumns.stream().map(_ -> "?").toList();
        return "INSERT INTO %s (%s) VALUES (%s)".formatted(
            qualifiedTableName,
            String.join(", ", allColumns),
            String.join(", ", placeholders)
        );
    }

    @Override
    public void write(Chunk<? extends Map<String, String>> chunk) {
        // Validate all IDs first to ensure the entire chunk fails if any ID is invalid
        var rowsWithIds = new ArrayList<Map.Entry<Map<String, String>, Object>>();
        for (var row : chunk) {
            var id = determineRowId(row);
            rowsWithIds.add(Map.entry(row, id));
        }

        // Only write if all IDs are valid
        for (var entry : rowsWithIds) {
            var values = buildRowValues(entry.getKey(), entry.getValue());
            jdbcTemplate.update(insertSql, values);
        }
    }

    private Object determineRowId(Map<String, String> row) {
        // Try to find ID value in row (case-insensitive)
        var idValue = row.entrySet().stream()
            .filter(e -> e.getKey().equalsIgnoreCase(idDescriptor.columnName()))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElse(null);

        return switch (idDescriptor.dataType()) {
            case DataType.NumericType _ -> {
                if (idValue == null || idValue.isEmpty()) {
                    yield rowCounter.incrementAndGet();
                }
                try {
                    yield Long.parseLong(idValue);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid numeric ID: " + idValue, e);
                }
            }
            case DataType.StringType _ -> {
                if (idValue == null || idValue.isEmpty()) {
                    throw new IllegalStateException("String ID column '" + idDescriptor.columnName() +
                                                    "' is required but not provided in data");
                }
                yield idValue;
            }
            default -> throw new IllegalArgumentException("Unsupported ID type: " + idDescriptor.dataType());
        };
    }

    private Object[] buildRowValues(Map<String, String> row, Object id) {
        return Stream.concat(
            Stream.of(id),
            dataColumns.stream().map(col -> {
                var value = row.get(col);
                if (value == null || value.isEmpty()) {
                    return null;
                }
                var dataType = columnTypes.get(col);
                if (dataType == null) {
                    return value; // Fallback to string
                }
                return TypeParser.parseValue(value, dataType);
            })
        ).toArray();
    }
}
