package com.rorm.engine;

import com.rorm.metamodel.BasicAttribute;
import com.rorm.query.Query;
import com.rorm.query.Selector.MultiExprSelector;
import com.rorm.query.Selector.RootSelector;
import com.rorm.query.Selector.SingleExprSelector;
import lombok.RequiredArgsConstructor;
import org.jooq.*;

import static org.jooq.impl.DSL.*;

@RequiredArgsConstructor
class SubqueryTransformer {

    private final ExpressionTransformer expr;
    private final JoinCollector joinCollector;

    Field<?> transform(Query query) {
        var nestedCtx = expr.ctx().nested(query.from());
        return expr.withContext(nestedCtx, () -> transformInContext(query));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Field<?> transformInContext(Query query) {
        var ctx = expr.ctx();
        var selectFields = buildSelect(query);
        var fromStep = selectFields.from(ctx.rootTable());

        var joins = joinCollector.collectFromQuery(query);

        SelectJoinStep joinStep = fromStep;
        for (var join : joins) {
            if (join.leftJoinColumn() != null && join.rightJoinColumn() != null) {
                joinStep = joinStep.leftJoin(join.table())
                    .on(join.leftJoinColumn().eq((Field) join.rightJoinColumn()));
            }
        }

        var whereCondition = query.where() != null ? (Condition) expr.transform(query.where()) : noCondition();
        var conditionStep = joinStep.where(whereCondition);

        Select<?> result = conditionStep;
        if (query.groupBy() != null) {
            var groupByFields = query.groupBy().expressions().stream()
                .map(expr::transform)
                .toArray(GroupField[]::new);
            var groupStep = conditionStep.groupBy(groupByFields);
            result = groupStep;
            if (query.having() != null) {
                result = groupStep.having((Condition) expr.transform(query.having()));
            }
        }

        return field((Select) result);
    }

    private SelectSelectStep<?> buildSelect(Query query) {
        var selector = query.selector();
        return switch (selector) {
            case SingleExprSelector(var e, var distinct, var alias) -> {
                var f = alias != null ? expr.transform(e).as(alias) : expr.transform(e);
                yield distinct ? selectDistinct(f) : select(f);
            }
            case MultiExprSelector(var exprs, var distinct) -> {
                var fields = exprs.stream()
                    .map(ae -> ae.alias() != null ? expr.transform(ae.expression()).as(ae.alias()) : expr.transform(ae.expression()))
                    .toArray(Field[]::new);
                yield distinct ? selectDistinct(fields) : select(fields);
            }
            case RootSelector(var root, var distinct) -> {
                var fields = root.attributes().stream()
                    .filter(BasicAttribute.class::isInstance)
                    .map(BasicAttribute.class::cast)
                    .map(attr -> expr.ctx().resolveField(attr, expr.ctx().rootTable()))
                    .toArray(Field[]::new);
                yield distinct ? selectDistinct(fields) : select(fields);
            }
        };
    }
}
