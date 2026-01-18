package com.rorm.engine;

import com.rorm.metamodel.DataType;

/**
 * Categorizes DataTypes into broader categories for determining
 * appropriate data analysis operations.
 */
public enum TypeCategory {
    /**
     * Numeric types that support statistical operations like avg, stddev, histograms.
     */
    NUMERIC,

    /**
     * String and enum types that support frequency/distribution analysis.
     */
    CATEGORICAL,

    /**
     * Date, time, and datetime types that support temporal analysis.
     */
    TEMPORAL,

    /**
     * Boolean types that support true/false counting.
     */
    BOOLEAN,

    /**
     * Reference types (singular or plural) that support relationship analysis.
     */
    REFERENCE,

    /**
     * Collection/list types.
     */
    COLLECTION,

    /**
     * Type cannot be determined.
     */
    UNKNOWN;

    /**
     * Categorizes a DataType into the appropriate TypeCategory.
     *
     * @param dataType the data type to categorize
     * @return the category for the given type
     */
    public static TypeCategory categorize(DataType dataType) {
        if (dataType == null) {
            return UNKNOWN;
        }
        return switch (dataType) {
            case DataType.NumericType _ -> NUMERIC;
            case DataType.StringType _ -> CATEGORICAL;
            case DataType.EnumType _ -> CATEGORICAL;
            case DataType.DayOfWeekType _ -> CATEGORICAL;
            case DataType.BooleanType _ -> BOOLEAN;
            case DataType.DateType _ -> TEMPORAL;
            case DataType.TimeType _ -> TEMPORAL;
            case DataType.DateTimeType _ -> TEMPORAL;
            case DataType.TimezoneType _ -> TEMPORAL;
            case DataType.ListType _ -> COLLECTION;
        };
    }
}
