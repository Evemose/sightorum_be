package com.rorm.dataimport.type;

import com.rorm.metamodel.DataType;
import org.jspecify.annotations.Nullable;

/**
 * Coercion strategies that execute in-memory during import, row-by-row.
 * These can transform or validate individual values without needing other rows.
 */
public sealed interface InMemoryCoercion extends InvalidValueCoercionStrategy permits
    InMemoryCoercion.Skip,
    InMemoryCoercion.UseDefault,
    InMemoryCoercion.NullOnInvalid,
    InMemoryCoercion.ThrowOnInvalid,
    NumericCoercionStrategy {

    /**
     * Coerce an invalid value to a valid representation, or return null.
     *
     * @param value      Raw string value from the data source (may be null)
     * @param dataType   Target data type for the column
     * @param columnName Name of the column (for context)
     * @return Coerced value as appropriate Java type, or null to set database NULL
     */
    @Nullable
    Object coerce(@Nullable String value, DataType dataType, String columnName);

    /**
     * Skip invalid values - set them to NULL in the database.
     */
    record Skip() implements InMemoryCoercion {
        public static final Skip INSTANCE = new Skip();

        @Override
        public @Nullable Object coerce(@Nullable String value, DataType dataType, String columnName) {
            return null;
        }
    }

    /**
     * Use default values for invalid data based on data type.
     */
    record UseDefault(DefaultValueStrategy defaultValueStrategy) implements InMemoryCoercion {

        public static UseDefault withStandardDefaults() {
            return new UseDefault(DefaultValueStrategy.standardDefaults());
        }

        public static UseDefault withNoDefaults() {
            return new UseDefault(DefaultValueStrategy.noDefaults());
        }

        @Override
        public @Nullable Object coerce(@Nullable String value, DataType dataType, String columnName) {
            return defaultValueStrategy.getDefault(columnName, dataType);
        }
    }

    /**
     * Return NULL for invalid values (including overflow, parse errors, etc.).
     */
    record NullOnInvalid() implements InMemoryCoercion {
        public static final NullOnInvalid INSTANCE = new NullOnInvalid();

        @Override
        public @Nullable Object coerce(@Nullable String value, DataType dataType, String columnName) {
            return null;
        }
    }

    /**
     * Throw an exception when invalid values are encountered.
     */
    record ThrowOnInvalid() implements InMemoryCoercion {
        public static final ThrowOnInvalid INSTANCE = new ThrowOnInvalid();

        @Override
        public @Nullable Object coerce(@Nullable String value, DataType dataType, String columnName) {
            throw new IllegalArgumentException(
                "Invalid value '%s' for column '%s' with type %s"
                    .formatted(value, columnName, dataType.getClass().getSimpleName())
            );
        }
    }
}
