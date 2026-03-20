package com.rorm.ai.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rorm.ai.RormToolContext;
import com.rorm.dataimport.pipeline.profile.SchemaProfile;
import com.rorm.dataimport.pipeline.profile.SchemaProfileStore;
import com.rorm.dto.dense.DenseExpressionDto;
import com.rorm.engine.ExpressionAnalyzer;
import com.rorm.mapper.DenseQueryMapper;
import com.rorm.metamodel.BasicAttribute;
import com.rorm.metamodel.ModelSpace;
import com.rorm.metamodel.Root;
import com.rorm.query.Expression;
import com.rorm.query.Path;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataOverviewTool {

    private final ExpressionAnalyzer expressionAnalyzer;
    private final DenseQueryMapper denseQueryMapper;
    private final ObjectMapper objectMapper;
    private final SchemaProfileStore profileStore;

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
        DenseExpressionDto expressionDTO,
        ToolContext toolContext
    ) {
        try {
            log.debug("Analyzing expression on root '{}': {}", rootName, expressionDTO);

            var context = RormToolContext.from(toolContext);
            var modelSpace = context.modelSpace();
            var root = findRootByName(rootName, modelSpace);

            var expression = denseQueryMapper.expressionToEntity(expressionDTO, modelSpace, rootName);

            // Short-circuit: if expression is a simple attribute path, return pre-computed profile
            var cached = tryCachedProfile(expression, rootName, context.schema());
            if (cached != null) {
                log.info("Returning pre-computed profile for {}.{}", rootName,
                    ((BasicAttribute) ((Path) expression).target()).name());
                return cached;
            }

            var result = expressionAnalyzer.analyze(context.schema(), root, expression);
            log.debug("Expression category: {}", result.category());

            var response = new AnalysisResponse(
                true,
                result.category().name(),
                "Query executed successfully",
                result.rows().size(),
                result.rows(),
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

    private String errorResponse(String message) {
        try {
            return objectMapper.writeValueAsString(new AnalysisResponse(false, null, null, 0, null, message));
        } catch (JsonProcessingException e) {
            return "{\"success\":false,\"error\":\"" + message.replace("\"", "\\\"") + "\"}";
        }
    }

    private @Nullable String tryCachedProfile(Expression expression, String rootName, @Nullable String schema) {
        if (schema == null) {
            return null;
        }
        if (!(expression instanceof Path(
            com.rorm.metamodel.PathTarget target, Path parent
        ) && parent == null && target instanceof BasicAttribute basic)) {
            return null;
        }
        return profileStore.get(schema)
            .flatMap(p -> p.findEntity(rootName))
            .flatMap(e -> e.findAttribute(basic.name()))
            .map(attr -> {
                try {
                    return objectMapper.writeValueAsString(attr.statistics());
                } catch (JsonProcessingException e) {
                    return null;
                }
            })
            .orElse(null);
    }

    @Tool(
        name = "getSchemaProfile",
        description = """
            Get the high-level schema profile (Tier 1): entity names, row counts, summary flags, \
            and relationship graph. This is a compact overview of the entire imported dataset. \
            Use this first to understand the data landscape before drilling into specific entities."""
    )
    public String getSchemaProfile(ToolContext toolContext) {
        try {
            var context = RormToolContext.from(toolContext);
            if (context.schema() == null) {
                return errorResponse("No schema in context");
            }
            return profileStore.get(context.schema())
                .map(this::formatSchemaProfile)
                .orElse(errorResponse("No profile available for schema: " + context.schema()));
        } catch (Exception e) {
            return errorResponse("Failed to get schema profile: " + e.getMessage());
        }
    }

    private String formatSchemaProfile(SchemaProfile profile) {
        try {
            return objectMapper.writeValueAsString(profile);
        } catch (JsonProcessingException e) {
            return errorResponse("Failed to format schema profile");
        }
    }

    @Tool(
        name = "getEntityProfile",
        description = """
            Get the detailed profile for a specific entity (Tier 2): attribute names, types, \
            categories, null counts, and distinct counts. Use this when you need to understand \
            the structure and data quality of a specific entity."""
    )
    public String getEntityProfile(
        @ToolParam(description = "The entity (table) name to inspect") String entityName,
        ToolContext toolContext
    ) {
        try {
            var context = RormToolContext.from(toolContext);
            if (context.schema() == null) {
                return errorResponse("No schema in context");
            }
            return profileStore.get(context.schema())
                .flatMap(p -> p.findEntity(entityName))
                .map(entity -> {
                    try {
                        return objectMapper.writeValueAsString(entity);
                    } catch (JsonProcessingException e) {
                        return errorResponse("Serialization failed");
                    }
                })
                .orElse(errorResponse("Entity not found: " + entityName));
        } catch (Exception e) {
            return errorResponse("Failed to get entity profile: " + e.getMessage());
        }
    }

    @Tool(
        name = "getAttributeProfile",
        description = """
            Get the full distribution statistics for a specific attribute (Tier 3): \
            numeric stats (min/max/avg/stddev), categorical top values, temporal ranges, \
            or boolean distributions. Pre-computed — no query execution needed."""
    )
    public String getAttributeProfile(
        @ToolParam(description = "The entity (table) name") String entityName,
        @ToolParam(description = "The attribute name to inspect") String attributeName,
        ToolContext toolContext
    ) {
        try {
            var context = RormToolContext.from(toolContext);
            if (context.schema() == null) {
                return errorResponse("No schema in context");
            }
            return profileStore.get(context.schema())
                .flatMap(p -> p.findEntity(entityName))
                .flatMap(e -> e.findAttribute(attributeName))
                .map(attr -> {
                    try {
                        return objectMapper.writeValueAsString(attr);
                    } catch (JsonProcessingException e) {
                        return errorResponse("Serialization failed");
                    }
                })
                .orElse(errorResponse("Attribute not found: " + entityName + "." + attributeName));
        } catch (Exception e) {
            return errorResponse("Failed to get attribute profile: " + e.getMessage());
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
