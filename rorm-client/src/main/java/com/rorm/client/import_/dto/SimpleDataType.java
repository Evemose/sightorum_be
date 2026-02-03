package com.rorm.client.import_.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonValue;
import com.rorm.metamodel.DataType;

/**
 * Simple data type enum for REST API DTOs.
 * Maps to/from the metamodel DataType sealed interface.
 * <p>
 * Accepts aliases during deserialization (e.g., "STRING" -> TEXT).
 * Configure ObjectMapper with ACCEPT_CASE_INSENSITIVE_ENUMS for case-insensitive parsing.
 */
public enum SimpleDataType {
    @JsonAlias("STRING")
    TEXT,

    @JsonAlias("INT")
    INTEGER,

    @JsonAlias("LONG")
    BIGINT,

    @JsonAlias("NUMERIC")
    DECIMAL,

    @JsonAlias("BOOL")
    BOOLEAN,

    DATE,
    TIME,

    @JsonAlias("DATETIME")
    TIMESTAMP;

    /**
     * Converts metamodel DataType to SimpleDataType.
     */
    public static SimpleDataType fromMetamodel(DataType type) {
        if (type == null) {
            return TEXT;
        }
        return switch (type) {
            case DataType.StringType _ -> TEXT;
            case DataType.NumericType n -> n.scale() > 0 ? DECIMAL : (n.precision() > 10 ? BIGINT : INTEGER);
            case DataType.BooleanType _ -> BOOLEAN;
            case DataType.DateTimeType _ -> TIMESTAMP;
            case DataType.DateType _ -> DATE;
            case DataType.TimeType _ -> TIME;
            default -> TEXT;
        };
    }

    @JsonValue
    public String toValue() {
        return name();
    }

    /**
     * Converts SimpleDataType to metamodel DataType.
     */
    public DataType toMetamodel() {
        return switch (this) {
            case TEXT -> new DataType.StringType();
            case INTEGER -> new DataType.NumericType(10, 0);
            case BIGINT -> new DataType.NumericType(19, 0);
            case DECIMAL -> new DataType.NumericType(19, 4);
            case BOOLEAN -> new DataType.BooleanType();
            case DATE -> new DataType.DateType();
            case TIME -> new DataType.TimeType();
            case TIMESTAMP -> new DataType.DateTimeType();
        };
    }
}
