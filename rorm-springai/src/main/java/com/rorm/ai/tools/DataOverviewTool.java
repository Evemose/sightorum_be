package com.rorm.ai.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.ai.DataOverviewService;
import com.rorm.ai.RormAiProperties;
import com.rorm.dto.ExpressionDTO;
import com.rorm.engine.ExpressionTypeResolver;
import com.rorm.engine.QueryTransformer;
import com.rorm.engine.TypeCategory;
import com.rorm.mapper.ExpressionMapper;
import com.rorm.metamodel.ModelSpace;
import com.rorm.metamodel.Root;
import com.rorm.query.Expression;
import com.rorm.query.Path;
import com.rorm.query.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.*;

@Slf4j
@RequiredArgsConstructor
public class DataOverviewTool {

    private final DataOverviewService dataOverviewService;
    private final ExpressionTypeResolver typeResolver;
    private final ExpressionMapper expressionMapper;
    private final QueryTransformer queryTransformer;
    private final DSLContext dsl;
    private final ObjectMapper objectMapper;
    private final RormAiProperties properties;
    private final ModelSpace modelSpace;

    @Tool(
        name = "analyzeExpression",
        description = """
            Analyze an expression by generating and executing appropriate statistical queries based on the expression's data type.
            This tool should be used when you need to understand the distribution \
            or characteristics of data represented by a specific expression (often just a root attribute).
            For numeric expressions: returns statistics like min, max, avg, stddev, count.
            For temporal expressions: returns date range information.
            For boolean expressions: returns true/false/null distribution.
            For categorical/string expressions: returns frequency distribution of top values.
            For reference attributes: returns population statistics.
            """
    )
    public String analyzeExpression(
        @ToolParam(description = "The root entity table name to analyze")
        String rootName,
        @ToolParam(description = "The expression to analyze")
        ExpressionDTO expressionDTO
    ) {
        try {
            log.info("Analyzing expression on root '{}': {}", rootName, expressionDTO);

            // Find root
            var root = findRootByName(rootName);

            // Convert expression DTO to entity (use a dummy query DTO for context)
            var dummyQueryDTO = createDummyQueryDTO(rootName);
            var expression = expressionMapper.toEntity(expressionDTO, modelSpace, dummyQueryDTO);

            // Determine expression type category
            var category = typeResolver.categorize(expression, root);
            log.info("Expression category: {}", category);

            // Build appropriate query based on category
            Query analysisQuery = buildAnalysisQuery(root, expression, category);

            // Apply limit cap and execute
            var effectiveQuery = applyLimitCap(analysisQuery);
            var jooqQuery = queryTransformer.transform(effectiveQuery);
            var sql = jooqQuery.getSQL();

            log.info("Executing analysis SQL: {}", sql);

            @SuppressWarnings("unchecked")
            Result<Record> results = (Result<Record>) dsl.fetch(jooqQuery);

            var response = new AnalysisResponse(
                true,
                category.name(),
                sql,
                results.size(),
                formatResults(results),
                null
            );

            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            log.error("Expression analysis failed", e);
            return errorResponse("Analysis failed: " + e.getMessage());
        }
    }

    private Root findRootByName(String rootName) {
        return modelSpace.roots().stream()
            .filter(r -> r.primaryTableName().equals(rootName))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown root: " + rootName));
    }

    private com.rorm.dto.QueryDTO createDummyQueryDTO(String rootName) {
        // Create minimal QueryDTO for expression mapping context
        return new com.rorm.dto.QueryDTO(
            rootName,
            null, // fromAlias
            null, // selector
            new LinkedHashSet<>(), // joins
            null, // where
            null, // groupBy
            null, // having
            null, // orderBy
            null, // limit
            null  // offset
        );
    }

    private Query buildAnalysisQuery(Root root, Expression expression, TypeCategory category) {
        return switch (category) {
            case NUMERIC -> dataOverviewService.buildNumericStatsQuery(root, expression);
            case TEMPORAL -> dataOverviewService.buildTemporalRangeQuery(root, expression);
            case BOOLEAN -> dataOverviewService.buildBooleanDistributionQuery(root, expression);
            case CATEGORICAL -> dataOverviewService.buildCategoricalFrequencyQuery(root, expression, 20, false);
            case REFERENCE -> {
                // For reference attributes, check if it's a path and build appropriate query
                if (expression instanceof Path path) {
                    var resolvedType = typeResolver.resolveType(path, root);
                    if (resolvedType instanceof ExpressionTypeResolver.ResolvedType.SingularReference) {
                        yield dataOverviewService.buildSingularReferenceCountQuery(root, path);
                    } else if (resolvedType instanceof ExpressionTypeResolver.ResolvedType.PluralReference) {
                        yield dataOverviewService.buildPluralReferenceStatsQuery(root, path);
                    }
                }
                throw new IllegalArgumentException("Reference analysis requires a path expression to a reference attribute");
            }
            case COLLECTION, UNKNOWN -> dataOverviewService.buildDistinctCountQuery(root, expression);
        };
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
            return objectMapper.writeValueAsString(new AnalysisResponse(false, null, null, 0, null, message));
        } catch (JsonProcessingException e) {
            return "{\"success\":false,\"error\":\"" + message.replace("\"", "\\\"") + "\"}";
        }
    }

    public record AnalysisResponse(
        boolean success,
        String expressionCategory,
        String executedSql,
        int rowCount,
        List<Map<String, Object>> results,
        String error
    ) {}
}
