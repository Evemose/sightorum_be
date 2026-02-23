package com.rorm.dataimport.pipeline;

import com.rorm.dataimport.pipeline.listeners.ImportEventListener;
import com.rorm.dataimport.type.InMemoryCoercion;
import com.rorm.dataimport.type.NumericCoercionStrategy;
import com.rorm.dataimport.type.TypeParser;
import com.rorm.metamodel.DataType;
import com.rorm.metamodel.IdDescriptor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.batch.core.ChunkListener;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Array;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Writes data to a database table using column mappings from detected schema.
 */
@Slf4j
class DatabaseItemWriter implements ItemWriter<Map<String, Object>>, ChunkListener {

    private static final Map<Class<?>, Class<?>> PRIMITIVE_TO_WRAPPER = Map.of(
        int.class, Integer.class,
        long.class, Long.class,
        short.class, Short.class,
        boolean.class, Boolean.class,
        double.class, Double.class,
        float.class, Float.class,
        byte.class, Byte.class,
        char.class, Character.class
    );

    private static final Set<String> INVALID_TOKENS = Set.of("invalid", "n/a", "na", "null");

    private final JdbcTemplate jdbcTemplate;
    private final String qualifiedTableName;
    private final List<ColumnMapping> dataColumnMappings;
    private final IdDescriptor idDescriptor;
    private final String insertSql;
    private final AtomicLong rowCounter;
    private final Map<ImportRequest.AttributeKey, InMemoryCoercion> inMemoryCoercions;
    private final Map<ImportRequest.AttributeKey, com.rorm.dataimport.type.DbLevelCoercion> dbLevelCoercions;
    @SuppressWarnings("NotNullFieldNotInitialized")
    private ChunkContext chunkContext;

    /**
     * Creates a writer with column mappings from detected schema.
     *
     * @param jdbcTemplate       JDBC template for database operations
     * @param schema             Target database schema
     * @param tableName          Target table name
     * @param idDescriptor       ID descriptor for the table
     * @param columnMappings     Column mappings with source information
     * @param coercionStrategies All coercion strategies (InMemory and DbLevel)
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
            .filter(e -> e.getValue() instanceof InMemoryCoercion)
            .collect(java.util.stream.Collectors.toMap(
                Map.Entry::getKey,
                e -> (InMemoryCoercion) e.getValue()
            ));
        this.dbLevelCoercions = coercionStrategies.entrySet().stream()
            .filter(e -> e.getValue() instanceof com.rorm.dataimport.type.DbLevelCoercion)
            .collect(java.util.stream.Collectors.toMap(
                Map.Entry::getKey,
                e -> (com.rorm.dataimport.type.DbLevelCoercion) e.getValue()
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
        var rowsWithIds = new ArrayList<Map.Entry<Map<String, Object>, Object>>();
        for (var row : chunk) {
            var id = determineRowId(row);
            rowsWithIds.add(Map.entry(row, id));
        }

        var warnings = (List<String>) Objects.requireNonNull(
            chunkContext.getAttribute(ImportEventListener.WARNINGS_KEY)
        );

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
        var rawValue = findRawIdValue(row);
        return coerceIdValue(rawValue);
    }

    private Object[] buildRowValues(Map<String, Object> row, Object id, List<String> warnings) {
        return Stream.concat(
            Stream.of(id),
            dataColumnMappings.stream().map(mapping -> resolveColumnValue(row, mapping, warnings))
        ).toArray();
    }

    private @Nullable Object findRawIdValue(Map<String, Object> row) {
        return row.entrySet().stream()
            .filter(e -> e.getKey().equalsIgnoreCase(idDescriptor.columnName()))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElse(null);
    }

    private Object coerceIdValue(@Nullable Object idValue) {
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

    private @Nullable Object resolveColumnValue(Map<String, Object> row, ColumnMapping mapping, List<String> warnings) {
        var tableName = qualifiedTableName.substring(qualifiedTableName.indexOf('.') + 1);
        var key = new ImportRequest.AttributeKey(tableName, mapping.dbColumnName());
        var rawValue = row.get(mapping.sourceColumn());
        if (rawValue == null) {
            return null;
        }

        var coerced = handleCollectionTypes(rawValue, mapping);
        if (isDbLevelCoercionTarget(key, coerced)) {
            return null;
        }

        var strategy = inMemoryCoercions.get(key);
        if (isNumericCoercionTarget(strategy, mapping)) {
            return strategy.coerce(coerced.toString(), mapping.dataType(), mapping.dbColumnName());
        }
        if (isCompatibleType(coerced, mapping.dataType())) {
            return coerced;
        }
        return parseAndValidate(coerced, mapping, strategy, warnings);
    }

    private Object handleCollectionTypes(Object value, ColumnMapping mapping) {
        if (mapping.dataType() instanceof DataType.ListType(var elementType)) {
            if (value instanceof Collection<?> collection) {
                return toTypedArray(collection, elementType);
            } else if (value instanceof String stringValue) {
                return parseDelimitedCollectionValue(stringValue, mapping, elementType);
            }
        } else if (value instanceof Collection<?>) {
            throw new IllegalStateException("Expected ListType for collection value but got " + mapping.dataType());
        }
        return value;
    }

    private boolean isDbLevelCoercionTarget(ImportRequest.AttributeKey key, Object value) {
        return dbLevelCoercions.containsKey(key) && isInvalidToken(value.toString());
    }

    private boolean isNumericCoercionTarget(
        InMemoryCoercion strategy,
        ColumnMapping mapping
    ) {
        return strategy instanceof NumericCoercionStrategy
               && mapping.dataType() instanceof DataType.NumericType;
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

    private @Nullable Object parseAndValidate(
        Object value, ColumnMapping mapping,
        @Nullable InMemoryCoercion strategy, List<String> warnings
    ) {
        try {
            var parsed = TypeParser.parseValue(value.toString(), mapping.dataType());
            if (mapping.dataType() instanceof DataType.NumericType numericType
                && parsed instanceof Number number
                && !fitsWithinConstraints(number, numericType)) {
                if (strategy != null) {
                    return strategy.coerce(value.toString(), mapping.dataType(), mapping.dbColumnName());
                }
                warnings.add("Value %s exceeds precision/scale constraints for column %s. Setting to NULL."
                    .formatted(value, mapping.dbColumnName()));
                return null;
            }
            return parsed;
        } catch (Exception e) {
            if (strategy != null) {
                return strategy.coerce(value.toString(), mapping.dataType(), mapping.dbColumnName());
            }
            warnings.add("Failed to parse value '%s' for column '%s' with type %s: %s"
                .formatted(value, mapping.dbColumnName(), mapping.dataType(), e.getMessage()));
            return null;
        }
    }

    private Object[] toTypedArray(Collection<?> collection, DataType elementType) {
        var array = (Object[]) Array.newInstance(arrayComponentType(elementType), collection.size());
        var i = 0;
        for (var element : collection) {
            array[i++] = elementType.valueOf(element);
        }
        return array;
    }

    private Object[] parseDelimitedCollectionValue(String value, ColumnMapping mapping, DataType elementType) {
        if (value.isBlank()) {
            return (Object[]) Array.newInstance(arrayComponentType(elementType), 0);
        }

        var separator = mapping.collectionSeparator();
        if (separator == null || separator.isEmpty()) {
            throw new IllegalStateException(
                "Missing collection separator for list-typed column mapping: " + mapping.dbColumnName()
            );
        }

        var parts = value.split(Pattern.quote(separator), -1);
        var parsedValues = new ArrayList<>(parts.length);
        for (var part : parts) {
            var trimmed = part.trim();
            if (trimmed.isEmpty()) {
                throw new IllegalArgumentException(
                    "Empty collection element for column '%s'".formatted(mapping.dbColumnName())
                );
            }
            var parsed = TypeParser.parseValue(trimmed, elementType);
            parsedValues.add(elementType.valueOf(parsed));
        }
        return parsedValues.toArray((Object[]) Array.newInstance(arrayComponentType(elementType), parsedValues.size()));
    }

    private boolean isInvalidToken(@Nullable String value) {
        var trimmed = value == null ? "" : value.trim();
        return INVALID_TOKENS.contains(trimmed.toLowerCase());
    }

    private boolean fitsWithinConstraints(Number number, DataType.NumericType numericType) {
        var decimal = new java.math.BigDecimal(number.toString());
        return NumericCoercionStrategy.fitsWithinPrecision(decimal, numericType.precision(), numericType.scale());
    }

    private Class<?> arrayComponentType(DataType elementType) {
        var javaType = elementType.javaType();
        return PRIMITIVE_TO_WRAPPER.getOrDefault(javaType, javaType);
    }
}
