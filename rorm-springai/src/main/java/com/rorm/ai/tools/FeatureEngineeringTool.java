package com.rorm.ai.tools;

import com.rorm.ai.DeferredToolResult;
import com.rorm.ai.RormToolContext;
import com.rorm.ai.chat.AiChatService;
import com.rorm.ai.chat.ChatRequest;
import com.rorm.ai.chat.ThinkingLevel;
import com.rorm.ai.chat.ToolGroup;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class FeatureEngineeringTool {

    private static final String FEATURE_ENGINEER_SYSTEM = """
        FEATURE_ENGINEER_SYSTEM:
        
        You are a query construction specialist. You receive a feature description
        and query context, and you produce a working DenseQueryDto that computes
        the described feature.
        
        You have two tools:
        - executeQuery: runs a DenseQueryDto against the database, returns rows
        - getEntityProfile: returns column names, types, and sample values for a table
        
        PROCESS:
        1. If the query context is ambiguous, use getEntityProfile to inspect
           the relevant tables — confirm column names, types, join keys.
        2. Build the query incrementally:
           a. Start with a simple SELECT from the main table with LIMIT 5
           b. Add the derived feature expression
           c. Run it. If it fails, read the error, fix, retry.
        3. Once the query returns correct results, return it as your final answer.
        
        You may retry up to 5 times on errors. Common failure modes:
        - Wrong column name → use getEntityProfile to check
        - outerRef depth wrong → depth=1 means immediate parent query
        - Type mismatch in date arithmetic → use EXTRACT or function wrappers
        - Null handling → wrap with COALESCE where appropriate
        - 1:N joins produce duplicate rows → use DISTINCT or aggregation to fix
        
        OUTPUT FORMAT:
        Return the complete DenseQueryDto as JSON with // comments explaining
        each non-obvious pattern (subquery structure, outerRef usage, aggregation
        logic). Include the sample output rows so the caller can verify.
        
        If after 5 attempts the feature cannot be expressed, return:
        {
          "status": "FAILED",
          "featureName": "...",
          "lastError": "...",
          "suggestion": "alternative approach or simpler approximation"
        }
        
        DSL REFERENCE:
        {{QUERY_STRUCTURE}}
        """;

    private static final String FEATURE_ENGINEER_USER = """
        FEATURE_ENGINEER_USER:
        
        Feature: {{featureName}}
        
        Context:
        {{queryContext}}
        
        Description:
        {{featureDescription}}
        
        Metamodel:
        {{METAMODEL}}
        """;

    @Lazy
    private final AiChatService aiChatService;

    @Tool(description = """
        Delegates derived feature construction to a specialized sub-agent. \
        Describe the query context and desired feature in plain language; \
        the sub-agent builds a complete working query, validates it against \
        the database, and returns the query DTO with inline commentary \
        explaining the DSL patterns used.
        
        Use for any computed feature requiring subqueries, temporal joins, \
        correlated lookups, windowed aggregations, or multi-step logic.
        
        Cost: Moderate. Use freely for derived features, do not abuse for simple cases. The sub-agent retries \
        on errors so you do not need to understand DSL syntax yourself.
        
        Returns: A validated query DTO as JSON with sample output rows, \
        or a structured FAILED response with the last error and a \
        suggested alternative approach.""")
    public String engineerFeature(
        @ToolParam(description = """
            Short identifier for the feature, used as the output column alias.""")
        String featureName,

        @ToolParam(description = """
            Available tables and relationships for the computation. \
            Include table names, relevant columns with types, join keys, \
            and approximate row counts.""")
        String queryContext,

        @ToolParam(description = """
            Plain-language description of what to compute. Include \
            aggregation logic, join conditions, temporal or filtering \
            constraints, expected output grain, data type, nullability, \
            and expected value range.""")
        String featureDescription,

        ToolContext toolContext
    ) {
        var prompt = FEATURE_ENGINEER_USER
            .replace("{{featureName}}", featureName)
            .replace("{{queryContext}}", queryContext)
            .replace("{{featureDescription}}", featureDescription);

        var ctx = RormToolContext.from(toolContext);

        var name = ctx.id() == null ? ctx.stepJournal().randomUUID().toString() : ctx.id();
        return DeferredToolResult.defer(
            toolContext,
            ctx.stepJournal().runAsync(name, String.class, () -> aiChatService.call(
                ChatRequest.usingData(ctx.schema(), ctx.modelSpace())
                    .withThinkingLevel(ThinkingLevel.MEDIUM)
                    .withToolGroups(ToolGroup.QUERY)
                    .withModelName("claude-sonnet-4-6")
                    .withSystemPrompt(FEATURE_ENGINEER_SYSTEM)
                    .withSessionId("feature-engineering-" + name)
                    .ask(prompt)
            ))
        );
    }

}
