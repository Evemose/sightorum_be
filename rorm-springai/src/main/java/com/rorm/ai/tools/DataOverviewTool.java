package com.rorm.ai.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.ai.DataOverviewService;
import com.rorm.ai.RormAiProperties;
import com.rorm.ai.RormToolContext;
import com.rorm.dto.ExpressionDTO;
import com.rorm.dto.QueryDTO;
import com.rorm.engine.ExpressionTypeResolver;
import com.rorm.engine.TypeCategory;
import com.rorm.fetcher.Fetcher;
import com.rorm.mapper.ExpressionMapper;
import com.rorm.metamodel.ModelSpace;
import com.rorm.metamodel.Root;
import com.rorm.query.Expression;
import com.rorm.query.Path;
import com.rorm.query.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataOverviewTool {

    private final DataOverviewService dataOverviewService;
    private final ExpressionTypeResolver typeResolver;
    private final ExpressionMapper expressionMapper;
    private final Fetcher fetcher;
    private final ObjectMapper objectMapper;
    private final RormAiProperties properties;

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
        ExpressionDTO expressionDTO,
        ToolContext toolContext
    ) {
        try {
            log.info("Analyzing expression on root '{}': {}", rootName, expressionDTO);

            // Extract context
            var context = RormToolContext.from(toolContext);
            var modelSpace = context.modelSpace();

            // Find root
            var root = findRootByName(rootName, modelSpace);

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

            log.info("Executing analysis query on schema: {}", context.schema());

            // Execute query within schema context
            @SuppressWarnings("unchecked")
            var results = fetcher.withSchema(context.schema(), () ->
                fetcher.queryForType(effectiveQuery, () -> (Class<Map<String, Object>>) (Class<?>) Map.class)
            );

            var response = new AnalysisResponse(
                true,
                category.name(),
                "Query executed successfully",
                results.size(),
                results,
                null
            );

            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            log.error("Expression analysis failed", e);
            return errorResponse("Analysis failed: " + e.getMessage());
        }
    }

    private Root findRootByName(String rootName, ModelSpace modelSpace) {
        return modelSpace.roots().stream()
            .filter(r -> r.primaryTableName().equals(rootName))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown root: " + rootName));
    }

    private QueryDTO createDummyQueryDTO(String rootName) {
        // Create minimal QueryDTO for expression mapping context
        return new QueryDTO(
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
