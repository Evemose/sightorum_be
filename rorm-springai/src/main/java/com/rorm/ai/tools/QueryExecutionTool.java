package com.rorm.ai.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.ai.RormAiProperties;
import com.rorm.dto.QueryDTO;
import com.rorm.engine.QueryTransformer;
import com.rorm.mapper.QueryMapper;
import com.rorm.metamodel.ModelSpace;
import com.rorm.query.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
public class QueryExecutionTool {

    private final QueryTransformer queryTransformer;
    private final DSLContext dsl;
    private final ObjectMapper objectMapper;
    private final RormAiProperties properties;
    private final QueryMapper queryMapper;
    private final ModelSpace modelSpace;

    @Tool(
        name = "executeQuery",
        description = """
            Execute a database query against the schema. Build the Query object using the schema context provided.
            The Query uses Path expressions to reference attributes from Root entities.
            All query structure details (selectors, expressions, operators, etc.) are defined in the Query type schema.
            """
    )
    public String executeQuery(
        @ToolParam(
            description = "The query to execute. Must conform to the Query schema with proper from/selector/where/etc structure."
        ) QueryDTO queryDTO
    ) {
        try {
            log.info("Got query: {}", queryDTO);
            // Convert DTO to Query entity
            var query = queryMapper.toEntity(queryDTO, modelSpace);

            // Apply limit cap
            var effectiveQuery = applyLimitCap(query);

            var jooqQuery = queryTransformer.transform(effectiveQuery);
            var sql = jooqQuery.getSQL();

            log.info("Executing SQL: {}", sql);

            @SuppressWarnings("unchecked")
            Result<Record> results = (Result<Record>) dsl.fetch(jooqQuery);

            var response = new QueryResponse(
                true,
                sql,
                results.size(),
                formatResults(results),
                null
            );

            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            log.error("Query execution failed", e);
            return errorResponse("Query execution failed: " + e.getMessage());
        }
    }

    private Query applyLimitCap(Query query) {
        int maxResults = properties.maxQueryResults();
        Long currentLimit = query.limit();

        if (currentLimit == null || currentLimit > maxResults) {
            return query.withLimit((long) maxResults);
        }
        return query;
    }

    private List<Map<String, Object>> formatResults(Result<Record> results) {
        List<Map<String, Object>> formatted = new ArrayList<>();
        for (Record record : results) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (var field : record.fields()) {
                row.put(field.getName(), record.get(field));
            }
            formatted.add(row);
        }
        return formatted;
    }

    private String errorResponse(String message) {
        try {
            return objectMapper.writeValueAsString(new QueryResponse(false, null, 0, null, message));
        } catch (JsonProcessingException e) {
            return "{\"success\":false,\"error\":\"" + message.replace("\"", "\\\"") + "\"}";
        }
    }

    public record QueryResponse(
        boolean success,
        String executedSql,
        int rowCount,
        List<Map<String, Object>> results,
        String error
    ) {}
}
