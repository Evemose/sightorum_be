package com.rorm.ai.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.ai.RormAiProperties;
import com.rorm.ai.RormToolContext;
import com.rorm.dto.QueryDTO;
import com.rorm.fetcher.Fetcher;
import com.rorm.mapper.QueryMapper;
import com.rorm.query.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class QueryExecutionTool {

    private final Fetcher fetcher;
    private final ObjectMapper objectMapper;
    private final RormAiProperties properties;
    private final QueryMapper queryMapper;

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
        ) QueryDTO queryDTO,
        ToolContext toolContext
    ) {
        try {
            log.info("Got query: {}", queryDTO);
            // Extract context
            var context = RormToolContext.from(toolContext);
            var modelSpace = context.modelSpace();

            // Convert DTO to Query entity
            var query = queryMapper.toEntity(queryDTO, modelSpace);

            // Apply limit cap
            var effectiveQuery = applyLimitCap(query);

            log.info("Executing query on schema: {}", context.schema());

            // Execute query within schema context
            var results = fetcher.withSchema(context.schema(), () ->
                fetcher.queryForType(effectiveQuery, () -> {
                    @SuppressWarnings("unchecked")
                    var clazz = (Class<Map<String, Object>>) (Class<?>) Map.class;
                    return clazz;
                })
            );

            var response = new QueryResponse(
                true,
                "Query executed successfully",
                results.size(),
                results,
                null
            );

            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            log.error("Query execution failed", e);
            return errorResponse("Query execution failed: " + e.getMessage());
        }
    }

    private Query applyLimitCap(Query query) {
        var maxResults = properties.maxQueryResults();
        Long currentLimit = query.limit();

        if (currentLimit == null || currentLimit > maxResults) {
            return query.withLimit((long) maxResults);
        }
        return query;
    }


    private String errorResponse(String message) {
        try {
            return objectMapper.writeValueAsString(new QueryResponse(false, null, 0, null, message));
        } catch (JsonProcessingException _) {
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
