package com.rorm.dataimport.pipeline;

import com.rorm.dataimport.pipeline.listeners.ImportEventListener;
import com.rorm.dataimport.type.NumericCoercionStrategy;
import com.rorm.dataimport.type.TypeParser;
import com.rorm.metamodel.DataType;
import com.rorm.metamodel.IdDescriptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.ChunkListener;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Writes data to a database table using column mappings from detected schema.
 */
@Slf4j
class DatabaseItemWriter implements ItemWriter<Map<String, Object>>, ChunkListener {

    private final JdbcTemplate jdbcTemplate;
    private final String qualifiedTableName;
    private final List<ColumnMapping> dataColumnMappings;
    private final IdDescriptor idDescriptor;
    private final String insertSql;
    private final AtomicLong rowCounter;
    private final Map<ImportRequest.AttributeKey, com.rorm.dataimport.type.InMemoryCoercion> inMemoryCoercions;
    private ChunkContext chunkContext;

    /**
     * Creates a writer with column mappings from detected schema.
     *
     * @param jdbcTemplate        JDBC template for database operations
     * @param schema              Target database schema
     * @param tableName           Target table name
     * @param idDescriptor        ID descriptor for the table
     * @param columnMappings      Column mappings with source information
     * @param coercionStrategies  All coercion strategies (InMemory and DbLevel)
     */
    DatabaseItemWriter(
        JdbcTemplate jdbcTemplate,
        String schema,
        String tableName,
        IdDescriptor idDescriptor,
        List<ColumnMapping> columnMappings,
        Map<ImportRequest.AttributeKey, com.rorm.dataimport.type.InvalidValueCoercionStrategy> coercionStrategies
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.qualifiedTableName = "%s.%s".formatted(schema, tableName);
        this.idDescriptor = idDescriptor;

        // Filter to only InMemoryCoercion strategies - DbLevelCoercion executes post-import
        this.inMemoryCoercions = coercionStrategies.entrySet().stream()
            .filter(e -> e.getValue() instanceof com.rorm.dataimport.type.InMemoryCoercion)
            .collect(java.util.stream.Collectors.toMap(
                Map.Entry::getKey,
                e -> (com.rorm.dataimport.type.InMemoryCoercion) e.getValue()
            ));

        // Filter out the ID column from data columns
        this.dataColumnMappings = columnMappings.stream()
            .filter(mapping -> !mapping.dbColumnName().equalsIgnoreCase(idDescriptor.columnName()))
            .toList();

        this.insertSql = buildInsertSql();
        this.rowCounter = new AtomicLong(0);
    }

    private String buildInsertSql() {
        var allColumns = Stream.concat(
            Stream.of(idDescriptor.columnName()),
            dataColumnMappings.stream().map(ColumnMapping::dbColumnName)
        ).toList();
        var placeholders = allColumns.stream().map(_ -> "?").toList();
        return "INSERT INTO %s (%s) VALUES (%s)".formatted(
            qualifiedTableName,
            String.join(", ", allColumns),
            String.join(", ", placeholders)
        );
    }

    @Override
    public void beforeChunk(ChunkContext context) {
        this.chunkContext = context;
        context.setAttribute(ImportEventListener.WARNINGS_KEY, new ArrayList<String>());
        context.setAttribute(ImportEventListener.ROWS_IN_CHUNK_KEY, 0L);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void write(Chunk<? extends Map<String, Object>> chunk) {
        // Validate all IDs first to ensure the entire chunk fails if any ID is invalid
        var rowsWithIds = new ArrayList<Map.Entry<Map<String, Object>, Object>>();
        for (var row : chunk) {
            var id = determineRowId(row);
            rowsWithIds.add(Map.entry(row, id));
        }

        var warnings = (List<String>) chunkContext.getAttribute(ImportEventListener.WARNINGS_KEY);

        jdbcTemplate.batchUpdate(insertSql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                var entry = rowsWithIds.get(i);
                var values = buildRowValues(entry.getKey(), entry.getValue(), warnings);
                for (var j = 0; j < values.length; j++) {
                    ps.setObject(j + 1, values[j]);
                }
            }

            @Override
            public int getBatchSize() {
                return rowsWithIds.size();
            }
        });

        chunkContext.setAttribute(ImportEventListener.ROWS_IN_CHUNK_KEY, (long) chunk.size());
    }

    private Object determineRowId(Map<String, Object> row) {
        // Try to find ID value in row (case-insensitive)
        var idValue = row.entrySet().stream()
            .filter(e -> e.getKey().equalsIgnoreCase(idDescriptor.columnName()))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElse(null);

        return switch (idDescriptor.dataType()) {
            case DataType.NumericType _ -> {
                if (idValue == null) {
                    yield rowCounter.incrementAndGet();
                }
                if (idValue instanceof Number n) {
                    yield n.longValue();
                }
                var strValue = idValue.toString();
                if (strValue.isEmpty()) {
                    yield rowCounter.incrementAndGet();
                }
                try {
                    yield Long.parseLong(strValue);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid numeric ID: " + idValue, e);
                }
            }
            case DataType.StringType _ -> {
                if (idValue == null) {
                    throw new IllegalStateException("String ID column '" + idDescriptor.columnName() +
                                                    "' is required but not provided in data");
                }
                var strValue = idValue.toString();
                if (strValue.isBlank()) {
                    throw new IllegalStateException("String ID column '" + idDescriptor.columnName() +
                                                    "' is required but not provided in data");
                }
                yield strValue;
            }
            default -> throw new IllegalArgumentException("Unsupported ID type: " + idDescriptor.dataType());
        };
    }

    private Object[] buildRowValues(Map<String, Object> row, Object id, List<String> warnings) {
        var tableName = qualifiedTableName.substring(qualifiedTableName.indexOf('.') + 1);
        return Stream.concat(
            Stream.of(id),
            dataColumnMappings.stream().map(mapping -> {
                // Look up value using the source column name from CSV
                var value = row.get(mapping.sourceColumn());
                if (value == null) {
                    return null;
                }
                if (isCompatibleType(value, mapping.dataType())) {
                    return value;
                }
                try {
                    var parsed = TypeParser.parseValue(value.toString(), mapping.dataType());

                    if (
                        mapping.dataType() instanceof DataType.NumericType numericType && parsed instanceof Number number &&
                        !fitsWithinConstraints(number, numericType)
                    ) {
                        // Check if there's an in-memory coercion strategy for numeric overflow
                        var key = new ImportRequest.AttributeKey(tableName, mapping.dbColumnName());
                        var strategy = inMemoryCoercions.get(key);
                        if (strategy != null) {
                            return strategy.coerce(value.toString(), mapping.dataType(), mapping.dbColumnName());
                        }
                        warnings.add("Value %s exceeds precision/scale constraints for column %s. Setting to NULL."
                            .formatted(value, mapping.dbColumnName()));
                        return null;
                    }

                    return parsed;
                } catch (Exception e) {
                    // Check if there's an in-memory coercion strategy for this attribute
                    var key = new ImportRequest.AttributeKey(tableName, mapping.dbColumnName());
                    var strategy = inMemoryCoercions.get(key);
                    if (strategy != null) {
                        return strategy.coerce(value.toString(), mapping.dataType(), mapping.dbColumnName());
                    }
                    warnings.add("Failed to parse value '%s' for column '%s' with type %s: %s"
                        .formatted(value, mapping.dbColumnName(), mapping.dataType(), e.getMessage()));
                    return null;
                }
            })
        ).toArray();
    }

    private boolean isCompatibleType(Object value, DataType dataType) {
        return switch (dataType) {
            case DataType.NumericType _ -> value instanceof Number;
            case DataType.BooleanType _ -> value instanceof Boolean;
            case DataType.StringType _ -> value instanceof String;
            case DataType.ListType _ -> value instanceof List || value.getClass().isArray();
            default -> false;
        };
    }

    private boolean fitsWithinConstraints(Number number, DataType.NumericType numericType) {
        var decimal = new java.math.BigDecimal(number.toString());
        return NumericCoercionStrategy.fitsWithinPrecision(decimal, numericType.precision(), numericType.scale());
    }
}
