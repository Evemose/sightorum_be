package com.rorm.engine;

import com.rorm.metamodel.BasicAttribute;
import com.rorm.query.*;
import com.rorm.query.Join.JoinType;
import com.rorm.query.Query;
import com.rorm.query.Selector.MultiExprSelector;
import com.rorm.query.Selector.RootSelector;
import com.rorm.query.Selector.SingleExprSelector;
import lombok.RequiredArgsConstructor;
import org.jooq.*;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.jooq.impl.DSL.noCondition;
import static org.jooq.impl.DSL.table;

@Component
@RequiredArgsConstructor
public class QueryTransformer {

    private final DSLContext dsl;
    private final ExpressionTransformer expr;

    public Select<?> transform(Query query) {
        return expr.withContext(new QueryContext(query.from()), () -> doTransform(query));
    }

    private Select<?> doTransform(Query query) {
        var ctx = expr.ctx();
        Select<?> result = buildSelect(query.selector(), ctx).from(ctx.rootTable());
        result = applyJoins(result, collectAllJoins(query), query.joins());
        result = applyFilters(result, query.where(), query.groupBy(), query.having());
        result = applyOrderBy(result, query.orderBy());
        return applyPagination(result, query.limit(), query.offset());
    }

    private SelectSelectStep<?> buildSelect(Selector selector, QueryContext ctx) {
        return switch (selector) {
            case RootSelector(var root, var distinct) -> {
                var fields = root.attributes().stream()
                    .filter(BasicAttribute.class::isInstance)
                    .map(BasicAttribute.class::cast)
                    .map(attr -> ctx.resolveField(attr, ctx.rootTable()))
                    .toArray(Field[]::new);
                yield distinct ? dsl.selectDistinct(fields) : dsl.select(fields);
            }
            case SingleExprSelector(var e, var distinct, var alias) -> {
                var field = alias != null ? expr.transform(e).as(alias) : expr.transform(e);
                yield distinct ? dsl.selectDistinct(field) : dsl.select(field);
            }
            case MultiExprSelector(var exprs, var distinct) -> {
                var fields = exprs.stream()
                    .map(ae -> ae.alias() != null
                        ? expr.transform(ae.expression()).as(ae.alias())
                        : expr.transform(ae.expression()))
                    .toArray(Field[]::new);
                yield distinct ? dsl.selectDistinct(fields) : dsl.select(fields);
            }
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Select<?> applyJoins(Select<?> query, Set<QueryContext.JoinInfo> autoJoins, Set<Join> explicitJoins) {
        var result = query;

        for (var join : autoJoins) {
            if (join.leftJoinColumn() != null && join.rightJoinColumn() != null) {
                result = ((SelectJoinStep<?>) result).leftJoin(join.table())
                    .on(join.leftJoinColumn().eq((Field) join.rightJoinColumn()));
            }
        }

        for (var join : explicitJoins) {
            var condition = join.onCondition() != null
                ? (Condition) expr.transform(join.onCondition())
                : noCondition();
            result = applyExplicitJoin((SelectJoinStep<?>) result, join.joinType(), condition);
        }

        return result;
    }

    private Set<QueryContext.JoinInfo> collectAllJoins(Query query) {
        var joins = new LinkedHashSet<QueryContext.JoinInfo>();
        collectJoinsFromSelector(query.selector(), joins);
        if (query.where() != null) {
            joins.addAll(expr.collectJoins(query.where()));
        }
        if (query.groupBy() != null) {
            joins.addAll(expr.collectJoins(query.groupBy().expression()));
        }
        if (query.having() != null) {
            joins.addAll(expr.collectJoins(query.having()));
        }
        if (query.orderBy() != null) {
            joins.addAll(expr.collectJoins(query.orderBy().expression()));
        }
        return joins;
    }

    private Select<?> applyFilters(Select<?> query, Expression where, GroupBy groupBy, Expression having) {
        var result = query;
        if (where != null) {
            result = ((SelectWhereStep<?>) result).where((Condition) expr.transform(where));
        }
        if (groupBy != null) {
            result = ((SelectGroupByStep<?>) result).groupBy(expr.transform(groupBy.expression()));
        }
        if (having != null) {
            result = ((SelectHavingStep<?>) result).having((Condition) expr.transform(having));
        }
        return result;
    }

    private Select<?> applyOrderBy(Select<?> query, OrderBy orderBy) {
        if (orderBy == null) {
            return query;
        }
        var field = expr.transform(orderBy.expression());
        return ((SelectOrderByStep<?>) query).orderBy(orderBy.ascending() ? field.asc() : field.desc());
    }

    private Select<?> applyPagination(Select<?> query, Long limit, Long offset) {
        var result = query;
        if (limit != null) {
            result = ((SelectLimitStep<?>) result).limit(limit.intValue());
        }
        if (offset != null) {
            result = ((SelectOffsetStep<?>) result).offset(offset.intValue());
        }
        return result;
    }

    private Select<?> applyExplicitJoin(SelectJoinStep<?> step, JoinType type, Condition condition) {
        return switch (type) {
            case INNER -> step.innerJoin(table("explicit")).on(condition);
            case LEFT -> step.leftJoin(table("explicit")).on(condition);
            case RIGHT -> step.rightJoin(table("explicit")).on(condition);
            case CROSS -> step.crossJoin(table("explicit"));
        };
    }

    private void collectJoinsFromSelector(Selector selector, Set<QueryContext.JoinInfo> joins) {
        switch (selector) {
            case RootSelector _ -> {
            }
            case SingleExprSelector(var e, _, _) -> joins.addAll(expr.collectJoins(e));
            case MultiExprSelector(var exprs, _) ->
                exprs.forEach(ae -> joins.addAll(expr.collectJoins(ae.expression())));
        }
    }
}
