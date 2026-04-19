package com.rorm.engine;

import com.rorm.query.*;
import com.rorm.query.Expression.*;

import java.util.function.Consumer;
import java.util.stream.Stream;

final class ExpressionTraversal {

    private ExpressionTraversal() {
    }

    static Stream<Expression> children(Expression expr) {
        return switch (expr) {
            case Path _, Literal _, Subquery _ -> Stream.empty();
            case FunctionCall(_, var args) -> args.stream();
            case Aggregation agg -> agg.filterWhere() != null
                ? Stream.concat(agg.arguments().stream(), Stream.of(agg.filterWhere()))
                : agg.arguments().stream();
            case WindowFunction(_, var args, var spec) -> {
                var s = args.stream();
                if (spec.partitionBy() != null) {
                    s = Stream.concat(s, spec.partitionBy().stream());
                }
                if (spec.orderBy() != null) {
                    s = Stream.concat(s, spec.orderBy().stream().map(OrderBy::expression));
                }
                yield s;
            }
            case BinaryExpression(var left, _, var right) -> Stream.of(left, right);
            case QuantifiedComparison(var left, _, _, var sub) -> Stream.<Expression>of(left, sub);
            case UnaryExpression(_, var operand) -> Stream.of(operand);
            case TernaryExpression(var first, _, var second, var third) -> Stream.of(first, second, third);
            case CaseExpression(var whens, var elseExpr) -> {
                var s = whens.stream().<Expression>flatMap(w -> Stream.of(w.condition(), w.result()));
                yield elseExpr != null ? Stream.concat(s, Stream.of(elseExpr)) : s;
            }
        };
    }

    static void forEachInQuery(Query query, Consumer<Expression> visitor) {
        switch (query.selector()) {
            case Selector.RootSelector _ -> {
            }
            case Selector.SingleExprSelector(var e, _, _) -> visitor.accept(e);
            case Selector.MultiExprSelector(var exprs, _) -> exprs.forEach(se -> visitor.accept(se.expression()));
        }
        if (query.where() != null) {
            visitor.accept(query.where());
        }
        if (query.groupBy() != null) {
            query.groupBy().expressions().forEach(visitor);
        }
        if (query.having() != null) {
            visitor.accept(query.having());
        }
        if (query.orderBy() != null) {
            query.orderBy().forEach(ob -> visitor.accept(ob.expression()));
        }
    }
}
