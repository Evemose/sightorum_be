package com.rorm.dataimport.type;

import com.rorm.metamodel.DataType;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.time.*;
import java.util.HashMap;
import java.util.Map;

/**
 * Strategy for providing default values when data is null or empty.
 * Supports per-column defaults with type-aware fallbacks using proper Java types.
 */
public class DefaultValueStrategy {

    private final Map<String, Object> columnDefaults;
    private final boolean useStandardDefaults;

    private DefaultValueStrategy(Map<String, Object> columnDefaults, boolean useStandardDefaults) {
        this.columnDefaults = columnDefaults;
        this.useStandardDefaults = useStandardDefaults;
    }

    /**
     * Creates a strategy with standard zero/empty defaults for all types:
     * - Numeric: BigDecimal.ZERO
     * - String: ""
     * - Boolean: false
     * - Date: LocalDate.EPOCH (1970-01-01)
     * - Time: LocalTime.MIDNIGHT (00:00:00)
     * - DateTime: Instant.EPOCH (1970-01-01T00:00:00Z)
     * - Timezone: ZoneOffset.UTC
     * - DayOfWeek: DayOfWeek.MONDAY
     * - Enum: first value in enum
     */
    public static DefaultValueStrategy standardDefaults() {
        return new DefaultValueStrategy(Map.of(), true);
    }

    /**
     * Creates a strategy with no defaults - nulls are preserved.
     */
    public static DefaultValueStrategy noDefaults() {
        return new DefaultValueStrategy(Map.of(), false);
    }

    /**
     * Creates a strategy with custom per-column defaults.
     * Use builder pattern to customize.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Gets the default value for a given column and data type.
     * Returns null if no default should be applied (preserve null).
     * Returns a Java object of the appropriate type.
     */
    @Nullable
    public Object getDefault(String columnName, DataType dataType) {
        // Check for column-specific default first
        var columnDefault = columnDefaults.get(columnName);
        if (columnDefault != null) {
            return columnDefault;
        }

        // Fall back to standard type defaults if enabled
        if (!useStandardDefaults) {
            return null;
        }

        return switch (dataType) {
            case DataType.NumericType _ -> BigDecimal.ZERO;
            case DataType.StringType _ -> "";
            case DataType.BooleanType _ -> Boolean.FALSE;
            case DataType.DateType _ -> LocalDate.EPOCH;
            case DataType.TimeType _ -> LocalTime.MIDNIGHT;
            case DataType.DateTimeType _ -> Instant.EPOCH;
            case DataType.TimezoneType _ -> ZoneOffset.UTC;
            case DataType.DayOfWeekType _ -> DayOfWeek.MONDAY;
            case DataType.EnumType enumType -> enumType.values().length > 0 ? enumType.values()[0] : "";
            case DataType.ListType _ -> "";
        };
    }

    /**
     * Builder for creating custom DefaultValueStrategy.
     */
    public static class Builder {
        private final Map<String, Object> columnDefaults = new HashMap<>();
        private boolean useStandardDefaults = true;

        /**
         * Set a default value for a specific column using a Java object.
         */
        public Builder columnDefault(String columnName, Object defaultValue) {
            columnDefaults.put(columnName, defaultValue);
            return this;
        }

        /**
         * Set defaults for multiple columns at once.
         */
        public Builder columnDefaults(Map<String, Object> defaults) {
            columnDefaults.putAll(defaults);
            return this;
        }

        /**
         * Enable or disable standard type-based defaults as fallback.
         * When disabled, only column-specific defaults are used.
         */
        public Builder useStandardDefaults(boolean useStandardDefaults) {
            this.useStandardDefaults = useStandardDefaults;
            return this;
        }

        public DefaultValueStrategy build() {
            return new DefaultValueStrategy(Map.copyOf(columnDefaults), useStandardDefaults);
        }
    }
}
