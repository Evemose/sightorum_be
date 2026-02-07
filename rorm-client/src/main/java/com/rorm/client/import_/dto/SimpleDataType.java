package com.rorm.client.import_.dto;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.rorm.metamodel.DataType;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Simple data type for REST API DTOs.
 * Maps to/from the metamodel DataType sealed interface.
 * <p>
 * Serialized as a plain string for simple types (e.g., "TEXT", "INTEGER")
 * and as an object for ENUM with values (e.g., {"type": "ENUM", "values": ["A", "B"]}).
 * <p>
 * Accepts aliases during deserialization (e.g., "STRING" -> TEXT).
 */
@JsonSerialize(using = SimpleDataType.Serializer.class)
@JsonDeserialize(using = SimpleDataType.Deserializer.class)
public sealed interface SimpleDataType permits
    SimpleDataType.Text,
    SimpleDataType.Integer,
    SimpleDataType.Bigint,
    SimpleDataType.Decimal,
    SimpleDataType.Bool,
    SimpleDataType.Date,
    SimpleDataType.Time,
    SimpleDataType.Timestamp,
    SimpleDataType.Enum,
    SimpleDataType.ListType {

    // Convenience constants for singleton types
    Text TEXT = new Text();
    Integer INTEGER = new Integer();
    Bigint BIGINT = new Bigint();
    Decimal DECIMAL = new Decimal();
    Bool BOOLEAN = new Bool();
    Date DATE = new Date();
    Time TIME = new Time();
    Timestamp TIMESTAMP = new Timestamp();

    /**
     * Converts metamodel DataType to SimpleDataType.
     */
    static SimpleDataType fromMetamodel(DataType type) {
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
            case DataType.EnumType e -> e.values() != null && e.values().length > 0
                ? new Enum(new LinkedHashSet<>(List.of(e.values())))
                : new Enum(null);
            case DataType.ListType l -> new ListType(fromMetamodel(l.elementType()));
            default -> TEXT;
        };
    }

    /**
     * Parses a type name string (case-insensitive, with aliases) into a SimpleDataType.
     */
    static SimpleDataType fromString(String name) {
        return switch (name.toUpperCase()) {
            case "TEXT", "STRING" -> TEXT;
            case "INTEGER", "INT" -> INTEGER;
            case "BIGINT", "LONG" -> BIGINT;
            case "DECIMAL", "NUMERIC" -> DECIMAL;
            case "BOOLEAN", "BOOL" -> BOOLEAN;
            case "DATE" -> DATE;
            case "TIME" -> TIME;
            case "TIMESTAMP", "DATETIME" -> TIMESTAMP;
            case "ENUM" -> new Enum(null);
            case "LIST" -> new ListType(TEXT);
            default -> throw new IllegalArgumentException("Unknown SimpleDataType: " + name);
        };
    }

    /**
     * Converts SimpleDataType to metamodel DataType.
     */
    default DataType toMetamodel() {
        return switch (this) {
            case Text _ -> new DataType.StringType();
            case Integer _ -> new DataType.NumericType(10, 0);
            case Bigint _ -> new DataType.NumericType(19, 0);
            case Decimal _ -> new DataType.NumericType(19, 4);
            case Bool _ -> new DataType.BooleanType();
            case Date _ -> new DataType.DateType();
            case Time _ -> new DataType.TimeType();
            case Timestamp _ -> new DataType.DateTimeType();
            case Enum e -> new DataType.EnumType(
                e.values() != null ? e.values().toArray(String[]::new) : new String[0]
            );
            case ListType l -> new DataType.ListType(l.elementType().toMetamodel());
        };
    }

    /**
     * Returns the canonical type name string (e.g., "TEXT", "ENUM").
     */
    default String typeName() {
        return switch (this) {
            case Text _ -> "TEXT";
            case Integer _ -> "INTEGER";
            case Bigint _ -> "BIGINT";
            case Decimal _ -> "DECIMAL";
            case Bool _ -> "BOOLEAN";
            case Date _ -> "DATE";
            case Time _ -> "TIME";
            case Timestamp _ -> "TIMESTAMP";
            case Enum _ -> "ENUM";
            case ListType _ -> "LIST";
        };
    }

    record Text() implements SimpleDataType {}

    record Integer() implements SimpleDataType {}

    record Bigint() implements SimpleDataType {}

    record Decimal() implements SimpleDataType {}

    record Bool() implements SimpleDataType {}

    record Date() implements SimpleDataType {}

    record Time() implements SimpleDataType {}

    record Timestamp() implements SimpleDataType {}

    record Enum(Set<String> values) implements SimpleDataType {}

    record ListType(SimpleDataType elementType) implements SimpleDataType {}

    class Serializer extends JsonSerializer<SimpleDataType> {
        @Override
        public void serialize(SimpleDataType value, JsonGenerator gen, SerializerProvider provider) throws IOException {
            switch (value) {
                case Enum(var values) when values != null && !values.isEmpty() -> {
                    gen.writeStartObject();
                    gen.writeStringField("type", "ENUM");
                    gen.writeArrayFieldStart("values");
                    for (var v : values) {
                        gen.writeString(v);
                    }
                    gen.writeEndArray();
                    gen.writeEndObject();
                }
                case ListType(var elementType) -> {
                    gen.writeStartObject();
                    gen.writeStringField("type", "LIST");
                    gen.writeFieldName("elementType");
                    serialize(elementType, gen, provider);
                    gen.writeEndObject();
                }
                default -> gen.writeString(value.typeName());
            }
        }
    }

    class Deserializer extends JsonDeserializer<SimpleDataType> {
        @Override
        public SimpleDataType deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
            if (p.currentToken() == JsonToken.VALUE_STRING) {
                return fromString(p.getText());
            } else if (p.currentToken() == JsonToken.START_OBJECT) {
                var node = (JsonNode) p.getCodec().readTree(p);
                return deserializeNode(node, p);
            }
            throw JsonMappingException.from(p, "Expected string or object for SimpleDataType");
        }

        private SimpleDataType deserializeNode(JsonNode node, JsonParser p) throws IOException {
            if (node.isTextual()) {
                return fromString(node.asText());
            } else if (node.isObject()) {
                var typeNode = node.get("type");
                if (typeNode == null) {
                    throw JsonMappingException.from(p, "Missing 'type' field in SimpleDataType object");
                }
                var typeName = typeNode.asText();
                if ("LIST".equalsIgnoreCase(typeName)) {
                    var elementNode = node.get("elementType");
                    var elementType = elementNode != null
                        ? deserializeNode(elementNode, p)
                        : TEXT;
                    return new ListType(elementType);
                }
                var base = fromString(typeName);
                if (base instanceof Enum && node.has("values")) {
                    var valuesNode = node.get("values");
                    var values = new LinkedHashSet<String>();
                    for (var v : valuesNode) {
                        values.add(v.asText());
                    }
                    return new Enum(values);
                }
                return base;
            }
            throw JsonMappingException.from(p, "Expected string or object for SimpleDataType");
        }
    }
}
