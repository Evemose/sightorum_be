package com.rorm.dataimport.type;

import com.rorm.metamodel.DataType;

/**
 * Coercion strategies that execute after import using database-level operations.
 * These require analyzing the entire dataset and use SQL UPDATE statements.
 *
 * <h3>Execution Model</h3>
 * <p>After initial import (with NULL for invalid values), these strategies execute
 * SQL operations to fill/impute values based on patterns in the data:</p>
 * <pre>
 * UPDATE schema.table
 * SET column = (SELECT ... FROM schema.table WHERE ...)
 * WHERE column IS NULL
 * </pre>
 */
public sealed interface DbLevelCoercion extends InvalidValueCoercionStrategy permits
    DbLevelCoercion.ForwardFill,
    DbLevelCoercion.BackwardFill,
    DbLevelCoercion.UseMean,
    DbLevelCoercion.UseMedian,
    DbLevelCoercion.UseMode {

    /**
     * Quote an identifier for safe SQL usage.
     */
    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    /**
     * Generate SQL to apply this coercion strategy to NULL values in a column.
     *
     * @param schema       Database schema name
     * @param tableName    Table name
     * @param columnName   Column name
     * @param dataType     Column data type
     * @param idColumnName Name of the ID column for ordering
     * @return SQL statement to execute post-import
     */
    String generateSql(String schema, String tableName, String columnName, DataType dataType, String idColumnName);

    /**
     * Forward fill - propagate last valid value forward to fill NULLs.
     * SQL: UPDATE ... SET col = (SELECT col FROM table WHERE col IS NOT NULL AND id < current.id ORDER BY id DESC LIMIT 1)
     */
    record ForwardFill() implements DbLevelCoercion {
        public static final ForwardFill INSTANCE = new ForwardFill();

        @Override
        public String generateSql(String schema, String tableName, String columnName, DataType dataType, String idColumnName) {
            var s = quote(schema);
            var t = quote(tableName);
            var c = quote(columnName);
            var id = quote(idColumnName);

            return """
                UPDATE %s.%s target
                SET %s = (
                    SELECT %s
                    FROM %s.%s source
                    WHERE source.%s IS NOT NULL
                      AND source.%s < target.%s
                    ORDER BY source.%s DESC
                    LIMIT 1
                )
                WHERE target.%s IS NULL
                """.formatted(
                s, t, c,
                c,
                s, t,
                c,
                id, id,
                id,
                c
            );
        }
    }

    /**
     * Backward fill - propagate next valid value backward to fill NULLs.
     */
    record BackwardFill() implements DbLevelCoercion {
        public static final BackwardFill INSTANCE = new BackwardFill();

        @Override
        public String generateSql(String schema, String tableName, String columnName, DataType dataType, String idColumnName) {
            var s = quote(schema);
            var t = quote(tableName);
            var c = quote(columnName);
            var id = quote(idColumnName);

            return """
                UPDATE %s.%s target
                SET %s = (
                    SELECT %s
                    FROM %s.%s source
                    WHERE source.%s IS NOT NULL
                      AND source.%s > target.%s
                    ORDER BY source.%s ASC
                    LIMIT 1
                )
                WHERE target.%s IS NULL
                """.formatted(
                s, t, c,
                c,
                s, t,
                c,
                id, id,
                id,
                c
            );
        }
    }

    /**
     * Use mean for numeric columns, mode for categorical/string columns.
     */
    record UseMean() implements DbLevelCoercion {
        public static final UseMean INSTANCE = new UseMean();

        @Override
        public String generateSql(String schema, String tableName, String columnName, DataType dataType, String idColumnName) {
            if (dataType instanceof DataType.NumericType) {
                var s = quote(schema);
                var t = quote(tableName);
                var c = quote(columnName);

                return """
                    UPDATE %s.%s
                    SET %s = (SELECT AVG(%s) FROM %s.%s WHERE %s IS NOT NULL)
                    WHERE %s IS NULL
                    """.formatted(
                    s, t, c,
                    c, s, t, c,
                    c
                );
            } else {
                // For non-numeric, use mode (most frequent value)
                return generateModeUpdateSql(schema, tableName, columnName);
            }
        }

        private String generateModeUpdateSql(String schema, String tableName, String columnName) {
            var s = quote(schema);
            var t = quote(tableName);
            var c = quote(columnName);

            return """
                UPDATE %s.%s
                SET %s = (
                    SELECT %s
                    FROM %s.%s
                    WHERE %s IS NOT NULL
                    GROUP BY %s
                    ORDER BY COUNT(*) DESC
                    LIMIT 1
                )
                WHERE %s IS NULL
                """.formatted(
                s, t, c,
                c,
                s, t,
                c,
                c,
                c
            );
        }
    }

    /**
     * Use median value for numeric columns.
     */
    record UseMedian() implements DbLevelCoercion {
        public static final UseMedian INSTANCE = new UseMedian();

        @Override
        public String generateSql(String schema, String tableName, String columnName, DataType dataType, String idColumnName) {
            if (!(dataType instanceof DataType.NumericType)) {
                throw new IllegalArgumentException("UseMedian only applicable to numeric columns");
            }

            var s = quote(schema);
            var t = quote(tableName);
            var c = quote(columnName);

            return """
                UPDATE %s.%s
                SET %s = (
                    SELECT PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY %s)
                    FROM %s.%s
                    WHERE %s IS NOT NULL
                )
                WHERE %s IS NULL
                """.formatted(
                s, t, c,
                c,
                s, t,
                c,
                c
            );
        }
    }

    /**
     * Use mode (most frequent value) for categorical columns.
     */
    record UseMode() implements DbLevelCoercion {
        public static final UseMode INSTANCE = new UseMode();

        @Override
        public String generateSql(String schema, String tableName, String columnName, DataType dataType, String idColumnName) {
            var s = quote(schema);
            var t = quote(tableName);
            var c = quote(columnName);

            return """
                UPDATE %s.%s
                SET %s = (
                    SELECT %s
                    FROM %s.%s
                    WHERE %s IS NOT NULL
                    GROUP BY %s
                    ORDER BY COUNT(*) DESC
                    LIMIT 1
                )
                WHERE %s IS NULL
                """.formatted(
                s, t, c,
                c,
                s, t,
                c,
                c,
                c
            );
        }
    }
}
