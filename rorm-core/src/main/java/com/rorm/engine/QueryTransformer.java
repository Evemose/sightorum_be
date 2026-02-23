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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.jooq.impl.DSL.noCondition;

@RequiredArgsConstructor
public class QueryTransformer {

    private final DSLContext dsl;
    private final ExpressionTransformer expr;
    private final JoinCollector joinCollector;

    public Select<?> transform(Query query) {
        return transform(query, null);
    }

    public Select<?> transform(Query query, String schema) {
        var context = schema != null
            ? new QueryContext(query.from(), schema)
            : new QueryContext(query.from());
        return expr.withContext(context, () -> doTransform(query));
    }

    private Select<?> doTransform(Query query) {
        var ctx = expr.ctx();
        Select<?> result = buildSelect(query.selector(), ctx).from(ctx.rootTable());
        result = applyJoins(result, collectAllJoins(query), query.joins(), ctx);
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
    private Select<?> applyJoins(Select<?> query, Set<QueryContext.JoinInfo> autoJoins, Set<Join> explicitJoins, QueryContext ctx) {
        var result = query;
        var remainingAutoJoins = new LinkedHashSet<>(autoJoins);

        for (var join : explicitJoins) {
            var joinedRootInfo = ctx.getOrRegisterJoinedRoot(join.aliasedRoot());

            var onConditionAutoJoins = join.onCondition() != null
                ? joinCollector.collectFromExpression(join.onCondition())
                : Set.<QueryContext.JoinInfo>of();
            for (var autoJoin : onConditionAutoJoins) {
                if (autoJoin.leftJoinColumn() != null && autoJoin.rightJoinColumn() != null) {
                    remainingAutoJoins.remove(autoJoin);
                    result = ((SelectJoinStep<?>) result).leftJoin(autoJoin.table())
                        .on(autoJoin.leftJoinColumn().eq((Field) autoJoin.rightJoinColumn()));
                }
            }

            var condition = join.onCondition() != null
                ? (Condition) expr.transform(join.onCondition())
                : noCondition();
            result = applyExplicitJoin((SelectJoinStep<?>) result, joinedRootInfo.table(), join.joinType(), condition);
        }

        // Apply automatic joins from path navigation (reference attributes)
        for (var join : remainingAutoJoins) {
            if (join.leftJoinColumn() != null && join.rightJoinColumn() != null) {
                result = ((SelectJoinStep<?>) result).leftJoin(join.table())
                    .on(join.leftJoinColumn().eq((Field) join.rightJoinColumn()));
            }
        }

        return result;
    }

    private Set<QueryContext.JoinInfo> collectAllJoins(Query query) {
        return joinCollector.collectFromQuery(query);
    }

    private Select<?> applyFilters(Select<?> query, Expression where, GroupBy groupBy, Expression having) {
        var result = query;
        if (where != null) {
            result = ((SelectWhereStep<?>) result).where((Condition) expr.transform(where));
        }
        if (groupBy != null) {
            var groupByFields = groupBy.expressions().stream()
                .map(expr::transform)
                .toArray(org.jooq.GroupField[]::new);
            result = ((SelectGroupByStep<?>) result).groupBy(groupByFields);
        }
        if (having != null) {
            result = ((SelectHavingStep<?>) result).having((Condition) expr.transform(having));
        }
        return result;
    }

    private Select<?> applyOrderBy(Select<?> query, List<OrderBy> orderByList) {
        if (orderByList == null || orderByList.isEmpty()) {
            return query;
        }
        var orderByFields = orderByList.stream()
            .map(ob -> {
                var field = expr.transform(ob.expression());
                return ob.ascending() ? field.asc() : field.desc();
            })
            .toArray(org.jooq.SortField<?>[]::new);
        return ((SelectOrderByStep<?>) query).orderBy(orderByFields);
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

    private Select<?> applyExplicitJoin(SelectJoinStep<?> step, Table<?> joinTable, JoinType type, Condition condition) {
        return switch (type) {
            case INNER -> step.innerJoin(joinTable).on(condition);
            case LEFT -> step.leftJoin(joinTable).on(condition);
            case RIGHT -> step.rightJoin(joinTable).on(condition);
            case CROSS -> step.crossJoin(joinTable);
        };
    }
}
