package com.rorm.client.chat;

import java.util.List;
import java.util.Map;

public final class ChatNodeSchema {

    public static final Map<String, Object> SCHEMA = Map.of(
        "type", "object",
        "properties", Map.of(
            "nodes", Map.of(
                "type", "array",
                "items", Map.of("anyOf", nodeVariants())
            )
        ),
        "required", List.of("nodes"),
        "additionalProperties", false
    );

    private ChatNodeSchema() {
    }

    private static List<Map<String, Object>> nodeVariants() {
        return List.of(
            nodeSchema("text", Map.of(
                "content", stringProp()
            ), List.of("content")),

            nodeSchema("quote", Map.of(
                "content", stringProp(),
                "source", stringProp()
            ), List.of("content")),

            nodeSchema("headline", Map.of(
                "level", Map.of("type", "integer", "enum", List.of(1, 2, 3)),
                "text", stringProp()
            ), List.of("level", "text")),

            nodeSchema("chart", Map.of(
                "chartType", stringProp(),
                "title", stringProp(),
                "description", stringProp(),
                "data", Map.of(
                    "type", "array",
                    "items", Map.of("type", "object")
                )
            ), List.of("chartType", "title")),

            nodeSchema("kpi_card", Map.of(
                "label", stringProp(),
                "value", stringProp(),
                "delta", stringProp(),
                "trend", Map.of("type", "string", "enum", List.of("up", "down", "flat"))
            ), List.of("label", "value")),

            nodeSchema("table", Map.of(
                "title", stringProp(),
                "columns", Map.of("type", "array", "items", stringProp()),
                "rows", Map.of(
                    "type", "array",
                    "items", Map.of("type", "array", "items", Map.of())
                )
            ), List.of("columns", "rows")),

            nodeSchema("callout", Map.of(
                "severity", Map.of("type", "string", "enum",
                    List.of("info", "warning", "error", "caveat")),
                "message", stringProp(),
                "detail", stringProp()
            ), List.of("severity", "message")),

            nodeSchema("analysis_view", Map.of(
                "title", stringProp(),
                "status", stringProp(),
                "runId", stringProp()
            ), List.of("title", "status")),

            nodeSchema("import", Map.of(
                "jobId", stringProp(),
                "targetSchema", stringProp()
            ), List.of("jobId", "targetSchema")),

            nodeSchema("ref", Map.of(
                "refType", Map.of("type", "string", "enum", List.of("descriptive", "causal")),
                "runId", stringProp()
            ), List.of("refType", "runId"))
        );
    }

    private static Map<String, Object> nodeSchema(String typeName,
                                                  Map<String, Object> props,
                                                  List<String> required) {
        var allProps = new java.util.LinkedHashMap<>(props);
        allProps.put("type", Map.of("type", "string", "const", typeName));

        var allRequired = new java.util.ArrayList<>(required);
        allRequired.addFirst("type");

        return Map.of(
            "type", "object",
            "properties", Map.copyOf(allProps),
            "required", List.copyOf(allRequired),
            "additionalProperties", false
        );
    }

    private static Map<String, Object> stringProp() {
        return Map.of("type", "string");
    }
}
