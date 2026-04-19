package com.rorm.engine;

import com.rorm.engine.handler.BooleanFieldUtils;
import com.rorm.metamodel.BasicAttribute;
import com.rorm.query.*;
import com.rorm.query.Join.JoinType;
import com.rorm.query.Path;
import com.rorm.query.Query;
import lombok.RequiredArgsConstructor;
import org.jooq.*;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static org.jooq.impl.DSL.noCondition;

@Component
@RequiredArgsConstructor
public class QueryTransformer {

    private final DSLContext dsl;
    private final ExpressionTransformer expr;

    @SuppressWarnings({"rawtypes", "unchecked"})
    static Select<?> applyAutoJoins(SelectJoinStep<?> current, Set<QueryContext.JoinInfo> autoJoins) {
        SelectJoinStep<?> result = current;
        for (var join : autoJoins) {
            if (join.leftJoinColumn() != null && join.rightJoinColumn() != null) {
                result = result.leftJoin(join.table())
                    .on(join.leftJoinColumn().eq((Field) join.rightJoinColumn()));
            }
        }
        return result;
    }

    public Select<?> transform(Query query) {
        return transform(query, null);
    }

    public Select<?> transform(Query query, String schema) {
        var cteNames = query.ctes() == null
            ? Set.<String>of()
            : query.ctes().stream().map(CteDefinition::name).collect(Collectors.toSet());
        var ctx = new QueryContext(query.from(), schema, cteNames);
        var autoJoins = ScopeResolver.resolveQueryJoins(query, ctx);
        return expr.withContext(ctx, () -> assemble(query, autoJoins));
    }

    private Select<?> assemble(Query query, Set<QueryContext.JoinInfo> autoJoins) {
        if (query.ctes() != null && !query.ctes().isEmpty()) {
            return assembleWithCtes(query, autoJoins);
        }

        var result = buildCore(query, autoJoins, dsl::select, dsl::selectDistinct);
        result = applySetOperations(result, query.selector(), query.setOperations());
        result = applyOrderBy(result, query.orderBy(), !query.setOperations().isEmpty());
        return applyPagination(result, query.limit(), query.offset(), query.withTies());
    }

    private Select<?> assembleWithCtes(Query query, Set<QueryContext.JoinInfo> autoJoins) {
        var ctes = Objects.requireNonNull(query.ctes());
        var first = ctes.getFirst();

        WithAsStep withAsStep = first.columns() != null && !first.columns().isEmpty()
            ? dsl.with(first.name(), first.columns().toArray(String[]::new))
            : dsl.with(first.name());
        var withStep = withAsStep.as(transformCteQuery(first));

        for (int i = 1; i < ctes.size(); i++) {
            var cte = ctes.get(i);
            WithAsStep nextWithAs = cte.columns() != null && !cte.columns().isEmpty()
                ? withStep.with(cte.name(), cte.columns().toArray(String[]::new))
                : withStep.with(cte.name());
            withStep = nextWithAs.as(transformCteQuery(cte));
        }

        var ws = withStep;
        var result = buildCore(query, autoJoins, ws::select, ws::selectDistinct);
        result = applySetOperations(result, query.selector(), query.setOperations());
        result = applyOrderBy(result, query.orderBy(), !query.setOperations().isEmpty());
        return applyPagination(result, query.limit(), query.offset(), query.withTies());
    }

    private Select<?> transformCteQuery(CteDefinition cte) {
        var cteQuery = cte.query();
        var outerCtx = expr.ctx();
        var cteCtx = new QueryContext(cteQuery.from(), outerCtx.schema(), outerCtx.cteNames());
        var cteJoins = ScopeResolver.resolveQueryJoins(cteQuery, cteCtx);
        return expr.withContext(cteCtx, () -> {
            var result = buildCore(cteQuery, cteJoins, DSL::select, DSL::selectDistinct);
            result = applyOrderBy(result, cteQuery.orderBy(), false);
            return applyPagination(result, cteQuery.limit(), cteQuery.offset(), cteQuery.withTies());
        });
    }

    private Select<?> buildCore(Query query, Set<QueryContext.JoinInfo> autoJoins,
                                SelectStarter select, SelectStarter selectDistinct) {
        var ctx = expr.ctx();
        var fields = expr.renderSelectorFields(query.selector(), ctx);
        var selectStep = query.selector().distinct() ? selectDistinct.select(fields) : select.select(fields);
        Select<?> result = selectStep.from(ctx.rootTable());
        result = applyJoins(result, autoJoins, query.joins(), ctx);
        return applyFilters(result, query.where(), query.groupBy(), query.having());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Select<?> applyJoins(Select<?> query, Set<QueryContext.JoinInfo> autoJoins,
                                 Set<Join> explicitJoins, QueryContext ctx) {
        var result = query;
        var remainingAutoJoins = new LinkedHashSet<>(autoJoins);

        for (var join : explicitJoins) {
            var joinedRootInfo = ctx.getOrRegisterJoinedRoot(join.aliasedRoot());
            var explicitJoinAlias = joinedRootInfo.table().getName();

            remainingAutoJoins.removeIf(autoJoin -> autoJoin.table().getName().equals(explicitJoinAlias));

            var onConditionAutoJoins = join.onCondition() != null
                ? ScopeResolver.resolveExpressionJoins(join.onCondition(), ctx)
                : Set.<QueryContext.JoinInfo>of();
            for (var autoJoin : onConditionAutoJoins) {
                if (autoJoin.leftJoinColumn() != null && autoJoin.rightJoinColumn() != null) {
                    if (autoJoin.table().getName().equals(explicitJoinAlias)) {
                        continue;
                    }
                    remainingAutoJoins.remove(autoJoin);
                    result = ((SelectJoinStep<?>) result).leftJoin(autoJoin.table())
                        .on(autoJoin.leftJoinColumn().eq((Field) autoJoin.rightJoinColumn()));
                }
            }

            if (join.joinType() == JoinType.CROSS_LATERAL || join.joinType() == JoinType.LEFT_LATERAL) {
                var readyAutoJoins = remainingAutoJoins.stream()
                    .filter(autoJoin -> !referencesAlias(autoJoin, explicitJoinAlias))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
                result = applyAutoJoins((SelectJoinStep<?>) result, readyAutoJoins);
                remainingAutoJoins.removeAll(readyAutoJoins);
            }

            var condition = join.onCondition() != null
                ? (Condition) expr.transform(join.onCondition())
                : noCondition();
            result = applyExplicitJoin((SelectJoinStep<?>) result, joinedRootInfo.table(), join.joinType(), condition);
        }

        return applyAutoJoins((SelectJoinStep<?>) result, remainingAutoJoins);
    }

    private Select<?> applyFilters(Select<?> query, Expression where, GroupBy groupBy, Expression having) {
        var result = query;
        if (where != null) {
            result = ((SelectWhereStep<?>) result).where(BooleanFieldUtils.asCondition(expr.transform(where)));
        }
        if (groupBy != null) {
            var groupByFields = groupBy.expressions().stream()
                .map(expr::transform)
                .toArray(org.jooq.GroupField[]::new);
            result = ((SelectGroupByStep<?>) result).groupBy(groupByFields);
        }
        if (having != null) {
            result = ((SelectHavingStep<?>) result).having(BooleanFieldUtils.asCondition(expr.transform(having)));
        }
        return result;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Select<?> applySetOperations(Select<?> query, Selector baseSelector, List<SetOperation> setOperations) {
        if (setOperations == null || setOperations.isEmpty()) {
            return query;
        }
        var result = query;
        for (var setOp : setOperations) {
            var normalizedSetQuery = normalizeSetOperationBranchSelector(setOp.query(), baseSelector);
            var otherQuery = transformSetOperationBranch(normalizedSetQuery);
            result = switch (setOp.type()) {
                case UNION -> ((SelectUnionStep) result).union((Select) otherQuery);
                case UNION_ALL -> ((SelectUnionStep) result).unionAll((Select) otherQuery);
                case INTERSECT -> ((SelectUnionStep) result).intersect((Select) otherQuery);
                case INTERSECT_ALL -> ((SelectUnionStep) result).intersectAll((Select) otherQuery);
                case EXCEPT -> ((SelectUnionStep) result).except((Select) otherQuery);
                case EXCEPT_ALL -> ((SelectUnionStep) result).exceptAll((Select) otherQuery);
            };
        }
        return result;
    }

    private Query normalizeSetOperationBranchSelector(Query setOpQuery, Selector baseSelector) {
        if (!(baseSelector instanceof Selector.MultiExprSelector(var baseExprs, _)
              && setOpQuery.selector() instanceof Selector.MultiExprSelector(var branchExprs, var distinct))) {
            return setOpQuery;
        }

        var branchByAlias = branchExprs.stream()
            .filter(se -> se.alias() != null && !se.alias().isBlank())
            .collect(Collectors.toMap(SelectedExpression::alias, se -> se, (a, _) -> a, java.util.LinkedHashMap::new));

        if (branchByAlias.size() != branchExprs.size()) {
            return setOpQuery;
        }

        var reordered = new java.util.LinkedHashSet<SelectedExpression>();
        for (var baseExpr : baseExprs) {
            var alias = baseExpr.alias();
            if (alias == null || alias.isBlank() || !branchByAlias.containsKey(alias)) {
                return setOpQuery;
            }
            reordered.add(branchByAlias.get(alias));
        }

        if (reordered.size() != branchExprs.size()) {
            return setOpQuery;
        }

        return setOpQuery.withSelector(new Selector.MultiExprSelector(reordered, distinct));
    }

    private Select<?> transformSetOperationBranch(Query setOpQuery) {
        var outerCtx = expr.ctx();
        var setCtx = new QueryContext(setOpQuery.from(), outerCtx.schema(), outerCtx.cteNames());
        var setJoins = ScopeResolver.resolveQueryJoins(setOpQuery, setCtx);
        return expr.withContext(setCtx, () -> buildCore(setOpQuery, setJoins, DSL::select, DSL::selectDistinct));
    }

    private Select<?> applyOrderBy(Select<?> query, List<OrderBy> orderByList, boolean setOperationQuery) {
        if (orderByList == null || orderByList.isEmpty()) {
            return query;
        }
        var orderByFields = orderByList.stream()
            .map(ob -> {
                var field = resolveOrderByField(ob.expression(), setOperationQuery);
                var sortField = ob.ascending() ? field.asc() : field.desc();
                if (ob.nullsHandling() != null) {
                    return switch (ob.nullsHandling()) {
                        case NULLS_FIRST -> sortField.nullsFirst();
                        case NULLS_LAST -> sortField.nullsLast();
                    };
                }
                return sortField;
            })
            .toArray(org.jooq.SortField<?>[]::new);
        return ((SelectOrderByStep<?>) query).orderBy(orderByFields);
    }

    private Field<?> resolveOrderByField(Expression expression, boolean setOperationQuery) {
        if (setOperationQuery
            && expression instanceof Path(var target, var parent)
            && parent == null
            && target instanceof BasicAttribute attr) {
            return DSL.field(DSL.name(attr.name()));
        }
        return expr.transform(expression);
    }

    private boolean referencesAlias(QueryContext.JoinInfo joinInfo, String alias) {
        return fieldReferencesAlias(joinInfo.leftJoinColumn(), alias)
               || fieldReferencesAlias(joinInfo.rightJoinColumn(), alias);
    }

    private boolean fieldReferencesAlias(Field<?> field, String alias) {
        if (field == null) {
            return false;
        }
        var qualifier = field.getQualifiedName().qualifier();
        return qualifier != null && alias.equals(qualifier.last());
    }

    private Select<?> applyPagination(Select<?> query, Long limit, Long offset, Boolean withTies) {
        var result = query;
        if (limit != null) {
            var limitStep = ((SelectLimitStep<?>) result).limit(limit.intValue());
            if (Boolean.TRUE.equals(withTies)) {
                result = limitStep.withTies();
            } else {
                result = limitStep;
            }
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
            case CROSS_LATERAL -> step.crossJoin(DSL.lateral(joinTable));
            case LEFT_LATERAL -> step.leftJoin(DSL.lateral(joinTable)).on(condition);
        };
    }

    @FunctionalInterface
    interface SelectStarter {
        SelectSelectStep<?> select(Field<?>... fields);
    }
}
