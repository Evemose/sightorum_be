package com.rorm.engine;

import com.rorm.engine.handler.BooleanFieldUtils;
import com.rorm.query.Query;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.jooq.*;
import org.springframework.stereotype.Component;

import static org.jooq.impl.DSL.*;

@Component
@RequiredArgsConstructor
class SubqueryTransformer {

    private final ExpressionTransformer expr;

    @PostConstruct
    void init() {
        expr.setSubqueryTransformer(this);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    Field<?> transform(Query query) {
        var nestedCtx = expr.ctx().nested(query.from());
        return expr.withContext(nestedCtx, () -> field((Select) buildInContext(query)));
    }

    private Select<?> buildInContext(Query query) {
        var ctx = expr.ctx();
        var autoJoins = ScopeResolver.resolveQueryJoins(query, ctx);

        var fields = expr.renderSelectorFields(query.selector(), ctx);
        var selectStep = query.selector().distinct() ? selectDistinct(fields) : select(fields);
        Select<?> result = QueryTransformer.applyAutoJoins(selectStep.from(ctx.rootTable()), autoJoins);

        if (query.where() != null) {
            result = ((SelectWhereStep<?>) result).where(BooleanFieldUtils.asCondition(expr.transform(query.where())));
        }
        if (query.groupBy() != null) {
            var groupByFields = query.groupBy().expressions().stream()
                .map(expr::transform)
                .toArray(GroupField[]::new);
            result = ((SelectGroupByStep<?>) result).groupBy(groupByFields);
        }
        if (query.having() != null) {
            result = ((SelectHavingStep<?>) result).having(BooleanFieldUtils.asCondition(expr.transform(query.having())));
        }
        return result;
    }

    Select<?> transformAsSelect(Query query) {
        var nestedCtx = expr.ctx().nested(query.from());
        return expr.withContext(nestedCtx, () -> buildInContext(query));
    }
}
