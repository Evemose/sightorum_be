package com.rorm.engine;

import com.rorm.query.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

final class ScopeResolver {

    private ScopeResolver() {
    }

    static Set<QueryContext.JoinInfo> resolveQueryJoins(Query query, QueryContext ctx) {
        var joins = new LinkedHashSet<QueryContext.JoinInfo>();
        resolveQuery(query, ctx, ctx, joins);
        return joins;
    }

    static Set<QueryContext.JoinInfo> resolveExpressionJoins(Expression expression, QueryContext ctx) {
        if (expression == null) {
            return Set.of();
        }
        var joins = new LinkedHashSet<QueryContext.JoinInfo>();
        resolve(expression, ctx, ctx, joins);
        return joins;
    }

    private static void resolveQuery(Query query, QueryContext ctx, QueryContext collectFor, Set<QueryContext.JoinInfo> joins) {
        ExpressionTraversal.forEachInQuery(query, expr -> resolve(expr, ctx, collectFor, joins));
    }

    private static void resolve(Expression expr, QueryContext ctx, QueryContext collectFor, Set<QueryContext.JoinInfo> joins) {
        switch (expr) {
            case Path path -> {
                var owner = ctx.findOwner(path);
                if (owner == collectFor) {
                    resolvePath(path, owner, joins);
                }
            }
            case Subquery(var query) -> resolveQuery(query, ctx.nested(query.from()), collectFor, joins);
            default -> ExpressionTraversal.children(expr).forEach(child -> resolve(child, ctx, collectFor, joins));
        }
    }

    private static void resolvePath(Path path, QueryContext ctx, Set<QueryContext.JoinInfo> joins) {
        if (PathUtils.isDirectReferenceId(path)) {
            var ownerPath = path.parent() != null ? path.parent().parent() : null;
            if (ownerPath != null) {
                resolvePath(ownerPath, ctx, joins);
            }
            return;
        }

        var effective = PathUtils.extendReferenceIfNeeded(path);
        var segments = new ArrayList<Path>();
        for (var current = effective; current != null; current = current.parent()) {
            segments.add(current);
        }
        Collections.reverse(segments);

        for (var segment : segments) {
            var joinInfo = ctx.resolveJoin(segment);
            if (joinInfo.leftJoinColumn() != null && joinInfo.rightJoinColumn() != null) {
                joins.add(joinInfo);
            }
        }
    }
}
