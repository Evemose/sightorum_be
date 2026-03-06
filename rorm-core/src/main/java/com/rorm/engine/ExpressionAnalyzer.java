package com.rorm.engine;

import com.rorm.fetcher.Fetcher;
import com.rorm.metamodel.Root;
import com.rorm.query.Expression;
import com.rorm.query.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Executes statistical analysis on an expression: categorizes, builds the appropriate
 * query, and executes it. Single code path for all analysis consumers.
 */
@Component
@RequiredArgsConstructor
public class ExpressionAnalyzer {

    private final DataOverviewService queryService;
    private final ExpressionTypeResolver typeResolver;
    private final Fetcher fetcher;

    public AnalysisResult analyze(String schema, Root root, Expression expression) {
        var category = typeResolver.categorize(expression, root);
        var query = queryService.buildAnalysisQuery(root, expression, category);
        return execute(schema, category, query);
    }

    @SuppressWarnings("unchecked")
    private AnalysisResult execute(String schema, TypeCategory category, Query query) {
        var results = fetcher.withSchema(schema, () ->
            fetcher.queryForType(query, () -> (Class<Map<String, Object>>) (Class<?>) Map.class));
        return new AnalysisResult(category, results);
    }

    public AnalysisResult analyze(String schema, Root root, Expression expression, TypeCategory category) {
        var query = queryService.buildAnalysisQuery(root, expression, category);
        return execute(schema, category, query);
    }

    public record AnalysisResult(TypeCategory category, List<Map<String, Object>> rows) {}
}
