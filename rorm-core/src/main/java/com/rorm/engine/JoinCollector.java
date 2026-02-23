package com.rorm.engine;

import com.rorm.query.*;
import com.rorm.query.Expression.*;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

@RequiredArgsConstructor
class JoinCollector {

    private final ExpressionTransformer expr;

    Set<QueryContext.JoinInfo> collectFromQuery(Query query) {
        var joins = new LinkedHashSet<QueryContext.JoinInfo>();
        collectFromQuery(query, 0, joins);
        return joins;
    }

    Set<QueryContext.JoinInfo> collectFromExpression(Expression expression) {
        if (expression == null) {
            return Set.of();
        }
        var joins = new LinkedHashSet<QueryContext.JoinInfo>();
        collectFromExpression(expression, 0, joins);
        return joins;
    }

    private void collectFromQuery(Query query, int depth, Set<QueryContext.JoinInfo> joins) {
        collectFromSelector(query.selector(), depth, joins);
        if (query.where() != null) {
            collectFromExpression(query.where(), depth, joins);
        }
        if (query.groupBy() != null) {
            query.groupBy().expressions().forEach(e -> collectFromExpression(e, depth, joins));
        }
        if (query.having() != null) {
            collectFromExpression(query.having(), depth, joins);
        }
        if (query.orderBy() != null) {
            query.orderBy().forEach(ob -> collectFromExpression(ob.expression(), depth, joins));
        }
    }

    private void collectFromSelector(Selector selector, int depth, Set<QueryContext.JoinInfo> joins) {
        switch (selector) {
            case Selector.RootSelector _ -> {
            }
            case Selector.SingleExprSelector(var e, _, _) -> collectFromExpression(e, depth, joins);
            case Selector.MultiExprSelector(var exprs, _) ->
                exprs.forEach(ae -> collectFromExpression(ae.expression(), depth, joins));
        }
    }

    @SuppressWarnings("java:S6916")
    private void collectFromExpression(Expression expression, int depth, Set<QueryContext.JoinInfo> joins) {
        switch (expression) {
            case Path path -> {
                if (depth == 0) {
                    collectJoinsFromPath(path, joins);
                }
            }
            case OuterRef outerRef -> {
                if (depth == outerRef.depth()) {
                    var outerCtx = expr.ctx().ancestor(outerRef.depth());
                    expr.withContext(outerCtx, () -> {
                        collectJoinsFromPath(outerRef.path(), joins);
                        return null;
                    });
                }
            }
            case Subquery(var query) -> {
                var nestedCtx = expr.ctx().nested(query.from());
                expr.withContext(nestedCtx, () -> {
                    collectFromQuery(query, depth + 1, joins);
                    return null;
                });
            }
            case FunctionCall(_, var args) -> args.forEach(arg -> collectFromExpression(arg, depth, joins));
            case Aggregation(_, var args, _) -> args.forEach(arg -> collectFromExpression(arg, depth, joins));
            case WindowFunction(_, var args, var spec) -> {
                args.forEach(arg -> collectFromExpression(arg, depth, joins));
                if (spec.partitionBy() != null) {
                    spec.partitionBy().forEach(e -> collectFromExpression(e, depth, joins));
                }
                if (spec.orderBy() != null) {
                    spec.orderBy().forEach(ob -> collectFromExpression(ob.expression(), depth, joins));
                }
            }
            case BinaryExpression(var left, _, var right) -> {
                collectFromExpression(left, depth, joins);
                collectFromExpression(right, depth, joins);
            }
            case UnaryExpression(_, var operand) -> collectFromExpression(operand, depth, joins);
            case TernaryExpression(var first, _, var second, var third) -> {
                collectFromExpression(first, depth, joins);
                collectFromExpression(second, depth, joins);
                collectFromExpression(third, depth, joins);
            }
            case Literal _ -> {
            }
        }
    }

    private void collectJoinsFromPath(Path path, Set<QueryContext.JoinInfo> joins) {
        var effectivePath = expr.extendReferencePathIfNeeded(path);
        var paths = new ArrayList<Path>();
        var current = effectivePath;
        while (current != null) {
            paths.add(current);
            current = current.parent();
        }
        Collections.reverse(paths);

        for (var p : paths) {
            var joinInfo = expr.ctx().resolveJoin(p);
            if (joinInfo.leftJoinColumn() != null && joinInfo.rightJoinColumn() != null) {
                joins.add(joinInfo);
            }
        }
    }
}
