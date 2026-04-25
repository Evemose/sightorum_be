package com.rorm.client.chat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rorm.client.chat.dto.ChatNodeStream;
import lombok.SneakyThrows;
import org.springframework.ai.util.json.schema.JsonSchemaGenerator;

import java.util.Map;
import java.util.Set;

public final class ChatNodeSchema {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * JSON-Schema keywords Anthropic's strict structured-output validator rejects.
     * Stripped wherever they appear in the tree.
     */
    private static final Set<String> UNSUPPORTED_KEYWORDS = Set.of(
        "maxItems", "minItems", "uniqueItems",
        "minLength",
        "exclusiveMinimum", "exclusiveMaximum",
        "pattern", "format", "multipleOf",
        "default",
        "contentEncoding", "contentMediaType"
    );

    public static final Map<String, Object> SCHEMA = build();

    private ChatNodeSchema() {
    }

    @SneakyThrows
    private static Map<String, Object> build() {
        var root = (ObjectNode) MAPPER.readTree(
            JsonSchemaGenerator.generateForType(ChatNodeStream.class));
        sanitize(root);
        return MAPPER.convertValue(root, new TypeReference<>() {});
    }

    /**
     * Walks the generated schema and brings every node into Anthropic strict-mode
     * compliance:
     * <ul>
     *   <li>Every {@code "type": "object"} node carries {@code "additionalProperties": false}.</li>
     *   <li>Unsupported keywords (maxItems, pattern, format, …) are stripped.</li>
     *   <li>Typeless leaves (e.g. Java {@code Object} fields that Spring AI lowers to
     *       {@code {description: ...}}) are widened to {@code anyOf: [string, number, boolean]}.</li>
     * </ul>
     */
    private static void sanitize(JsonNode node) {
        if (node instanceof ObjectNode obj) {
            stripUnsupported(obj);
            enforceAdditionalPropertiesFalse(obj);
            widenTypelessLeaf(obj);
            obj.forEach(ChatNodeSchema::sanitize);
        } else if (node.isArray()) {
            node.forEach(ChatNodeSchema::sanitize);
        }
    }

    private static void stripUnsupported(ObjectNode obj) {
        for (var k : UNSUPPORTED_KEYWORDS) {
            obj.remove(k);
        }
    }

    private static void enforceAdditionalPropertiesFalse(ObjectNode obj) {
        var type = obj.get("type");
        if (type != null && "object".equals(type.asText()) && !obj.has("additionalProperties")) {
            obj.put("additionalProperties", false);
        }
    }

    /**
     * A schema node that carries no constraint Anthropic accepts (no type, no union,
     * no ref, no const/enum) is rejected as "Schema type is missing". This happens
     * when a Java {@code Object} field appears in a record (e.g. {@code ReceiptItem.value}
     * which is documented as {@code string | number}). Anthropic's strict validator
     * doesn't accept {@code anyOf} of primitive types, so we collapse to {@code string}
     * — the LLM can stringify numbers when needed.
     */
    private static void widenTypelessLeaf(ObjectNode obj) {
        if (obj.has("type") || obj.has("anyOf") || obj.has("oneOf") || obj.has("allOf")
            || obj.has("$ref") || obj.has("enum") || obj.has("const")
            || obj.has("properties") || obj.has("items")) {
            return;
        }
        obj.put("type", "string");
    }
}
