package com.rorm.engine;

import com.rorm.metamodel.Attribute.BasicAttribute;
import com.rorm.query.*;
import com.rorm.query.Expression.*;
import com.rorm.query.Path;
import com.rorm.query.Selector.MultiExprSelector;
import com.rorm.query.Selector.RootSelector;
import com.rorm.query.Selector.SingleExprSelector;
import org.jooq.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.jooq.impl.DSL.*;

public final class QueryTransformer {

    private final DSLContext dsl;

    public QueryTransformer(DSLContext dsl) {
        this.dsl = dsl;
    }

    public org.jooq.Select<?> transform(com.rorm.query.Query query) {
        var ctx = new QueryContext(query.from());

        var select = buildSelect(query.selector(), ctx);
        org.jooq.Select<?> result = select.from(ctx.rootTable());

        var collectedJoins = collectRequiredJoins(query, ctx);
        result = applyAutoResolvedJoins(result, collectedJoins);
        result = applyExplicitJoins(result, query.joins(), ctx);
        result = applyWhere(result, query.where(), ctx);
        result = applyGroupBy(result, query.groupBy(), ctx);
        result = applyHaving(result, query.having(), ctx);
        result = applyOrderBy(result, query.orderBy(), ctx);
        result = applyLimit(result, query.limit());
        return applyOffset(result, query.offset());
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
            case SingleExprSelector(var expr, var distinct, var alias) -> {
                var field = transformExpression(expr, ctx);
                if (alias != null) {
                    field = field.as(alias);
                }
                yield distinct ? dsl.selectDistinct(field) : dsl.select(field);
            }
            case MultiExprSelector(var exprs, var distinct) -> {
                var fields = exprs.stream()
                    .map(ae -> {
                        var field = transformExpression(ae.expression(), ctx);
                        return ae.alias() != null ? field.as(ae.alias()) : field;
                    })
                    .toArray(Field[]::new);
                yield distinct ? dsl.selectDistinct(fields) : dsl.select(fields);
            }
        };
    }

    private Set<QueryContext.JoinInfo> collectRequiredJoins(com.rorm.query.Query query, QueryContext ctx) {
        var joins = new LinkedHashSet<QueryContext.JoinInfo>();

        collectJoinsFromSelector(query.selector(), ctx, joins);
        if (query.where() != null) {
            collectJoinsFromExpression(query.where(), ctx, joins);
        }
        if (query.groupBy() != null) {
            collectJoinsFromExpression(query.groupBy().expression(), ctx, joins);
        }
        if (query.having() != null) {
            collectJoinsFromExpression(query.having(), ctx, joins);
        }
        if (query.orderBy() != null) {
            collectJoinsFromExpression(query.orderBy().expression(), ctx, joins);
        }

        return joins;
    }

    private void collectJoinsFromSelector(Selector selector, QueryContext ctx, Set<QueryContext.JoinInfo> joins) {
        switch (selector) {
            case RootSelector _ -> {
            }
            case SingleExprSelector(var expr, _, _) -> collectJoinsFromExpression(expr, ctx, joins);
            case MultiExprSelector(var exprs, _) ->
                exprs.forEach(ae -> collectJoinsFromExpression(ae.expression(), ctx, joins));
        }
    }


    private void collectJoinsFromExpression(Expression expr, QueryContext ctx, Set<QueryContext.JoinInfo> joins) {
        switch (expr) {
            case Path path -> {
                var paths = new ArrayList<Path>();
                var current = path;
                while (current != null) {
                    paths.add(current);
                    current = current.parent();
                }
                Collections.reverse(paths);

                for (var p : paths) {
                    var joinInfo = ctx.resolveJoin(p);
                    if (joinInfo.leftJoinColumn() != null && joinInfo.rightJoinColumn() != null) {
                        joins.add(joinInfo);
                    }
                }
            }
            case FunctionCall(_, var args) -> args.forEach(arg -> collectJoinsFromExpression(arg, ctx, joins));
            case WindowFunction(_, var args, var windowSpec) -> {
                args.forEach(arg -> collectJoinsFromExpression(arg, ctx, joins));
                if (windowSpec.partitionBy() != null) {
                    windowSpec.partitionBy().forEach(e -> collectJoinsFromExpression(e, ctx, joins));
                }
                if (windowSpec.orderBy() != null) {
                    windowSpec.orderBy().forEach(ob -> collectJoinsFromExpression(ob.expression(), ctx, joins));
                }
            }
            case BinaryExpression(var left, _, var right) -> {
                collectJoinsFromExpression(left, ctx, joins);
                collectJoinsFromExpression(right, ctx, joins);
            }
            case UnaryExpression(_, var operand) -> collectJoinsFromExpression(operand, ctx, joins);
            case TernaryExpression(var first, _, var second, var third) -> {
                collectJoinsFromExpression(first, ctx, joins);
                collectJoinsFromExpression(second, ctx, joins);
                collectJoinsFromExpression(third, ctx, joins);
            }
            case Literal _ -> {
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private org.jooq.Select<?> applyAutoResolvedJoins(org.jooq.Select<?> query, Set<QueryContext.JoinInfo> joins) {
        var result = query;
        for (var join : joins) {
            if (join.leftJoinColumn() != null && join.rightJoinColumn() != null) {
                result = ((SelectJoinStep<?>) result).leftJoin(join.table()).on(
                    join.leftJoinColumn().eq((Field) join.rightJoinColumn())
                );
            }
        }
        return result;
    }

    private org.jooq.Select<?> applyExplicitJoins(org.jooq.Select<?> query, Set<Join> joins, QueryContext ctx) {
        var result = query;
        for (var join : joins) {
            var condition = join.onCondition() != null
                ? (Condition) transformExpression(join.onCondition(), ctx)
                : noCondition();

            result = switch (join.joinType()) {
                case INNER -> ((SelectJoinStep<?>) result).innerJoin(table("explicit")).on(condition);
                case LEFT -> ((SelectJoinStep<?>) result).leftJoin(table("explicit")).on(condition);
                case RIGHT -> ((SelectJoinStep<?>) result).rightJoin(table("explicit")).on(condition);
                case CROSS -> ((SelectJoinStep<?>) result).crossJoin(table("explicit"));
            };
        }
        return result;
    }

    private org.jooq.Select<?> applyWhere(org.jooq.Select<?> query, Expression where, QueryContext ctx) {
        return where != null ? ((SelectWhereStep<?>) query).where((Condition) transformExpression(where, ctx)) : query;
    }

    private org.jooq.Select<?> applyGroupBy(org.jooq.Select<?> query, GroupBy groupBy, QueryContext ctx) {
        if (groupBy == null) {
            return query;
        }
        return ((SelectGroupByStep<?>) query).groupBy(transformExpression(groupBy.expression(), ctx));
    }

    private org.jooq.Select<?> applyHaving(org.jooq.Select<?> query, Expression having, QueryContext ctx) {
        return having != null ? ((SelectHavingStep<?>) query).having((Condition) transformExpression(having, ctx)) : query;
    }

    private org.jooq.Select<?> applyOrderBy(org.jooq.Select<?> query, OrderBy orderBy, QueryContext ctx) {
        if (orderBy == null) {
            return query;
        }
        var field = transformExpression(orderBy.expression(), ctx);
        return ((SelectOrderByStep<?>) query).orderBy(orderBy.ascending() ? field.asc() : field.desc());
    }

    private org.jooq.Select<?> applyLimit(org.jooq.Select<?> query, Long limit) {
        return limit != null ? ((SelectLimitStep<?>) query).limit(limit.intValue()) : query;
    }

    private org.jooq.Select<?> applyOffset(org.jooq.Select<?> query, Long offset) {
        return offset != null ? ((SelectOffsetStep<?>) query).offset(offset.intValue()) : query;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Field<?> transformExpression(Expression expr, QueryContext ctx) {
        return switch (expr) {
            case Path path -> resolvePath(path, ctx);
            case FunctionCall(var name, var args) -> {
                var argFields = args.stream()
                    .map(arg -> transformExpression(arg, ctx))
                    .toArray(Field[]::new);
                yield function(name, Object.class, argFields);
            }
            case WindowFunction(var name, var args, var windowSpec) -> {
                var argFields = args.stream()
                    .map(arg -> transformExpression(arg, ctx))
                    .toArray(Field[]::new);

                var aggFunc = switch (name.toLowerCase()) {
                    case "count" -> count(argFields.length > 0 ? argFields[0] : asterisk());
                    case "sum" -> sum(argFields[0]);
                    case "avg" -> avg(argFields[0]);
                    case "min" -> min(argFields[0]);
                    case "max" -> max(argFields[0]);
                    case "row_number" -> rowNumber();
                    case "rank" -> rank();
                    case "dense_rank" -> denseRank();
                    default -> throw new UnsupportedOperationException("Unsupported window function: " + name);
                };

                var hasPartition = windowSpec.partitionBy() != null && !windowSpec.partitionBy().isEmpty();
                var hasOrder = windowSpec.orderBy() != null && !windowSpec.orderBy().isEmpty();

                if (hasPartition && hasOrder) {
                    var partitionFields = windowSpec.partitionBy().stream()
                        .map(e -> transformExpression(e, ctx))
                        .toArray(Field[]::new);
                    var orderFields = windowSpec.orderBy().stream()
                        .map(ob -> {
                            var field = transformExpression(ob.expression(), ctx);
                            return ob.ascending() ? field.asc() : field.desc();
                        })
                        .toArray(org.jooq.SortField[]::new);
                    yield aggFunc.over().partitionBy(partitionFields).orderBy(orderFields);
                } else if (hasPartition) {
                    var partitionFields = windowSpec.partitionBy().stream()
                        .map(e -> transformExpression(e, ctx))
                        .toArray(Field[]::new);
                    yield aggFunc.over().partitionBy(partitionFields);
                } else if (hasOrder) {
                    var orderFields = windowSpec.orderBy().stream()
                        .map(ob -> {
                            var field = transformExpression(ob.expression(), ctx);
                            return ob.ascending() ? field.asc() : field.desc();
                        })
                        .toArray(org.jooq.SortField[]::new);
                    yield aggFunc.over().orderBy(orderFields);
                } else {
                    yield aggFunc.over();
                }
            }
            case Literal(var value) -> inline(value);
            case BinaryExpression(var left, var op, var right) -> {
                var leftField = (Field) transformExpression(left, ctx);
                var rightField = (Field) transformExpression(right, ctx);
                yield switch (op) {
                    case EQUALS -> leftField.eq(rightField);
                    case NOT_EQUALS -> leftField.ne(rightField);
                    case GREATER_THAN -> leftField.gt(rightField);
                    case GREATER_THAN_OR_EQUAL -> leftField.ge(rightField);
                    case LESS_THAN -> leftField.lt(rightField);
                    case LESS_THAN_OR_EQUAL -> leftField.le(rightField);
                    case LIKE -> leftField.like(rightField);
                    case NOT_LIKE -> leftField.notLike(rightField);
                    case IN -> leftField.in(rightField);
                    case NOT_IN -> leftField.notIn(rightField);
                    case ADD -> leftField.add(rightField);
                    case SUBTRACT -> leftField.sub(rightField);
                    case MULTIPLY -> leftField.mul(rightField);
                    case DIVIDE -> leftField.div(rightField);
                    case MODULO -> leftField.mod(rightField);
                    case AND -> ((Condition) leftField).and((Condition) rightField);
                    case OR -> ((Condition) leftField).or((Condition) rightField);
                };
            }
            case UnaryExpression(var op, var operand) -> {
                var operandField = (Field) transformExpression(operand, ctx);
                yield switch (op) {
                    case IS_NULL -> operandField.isNull();
                    case IS_NOT_NULL -> operandField.isNotNull();
                    case IS_TRUE -> operandField.isTrue();
                    case IS_FALSE -> operandField.isFalse();
                    case NEGATE -> operandField.neg();
                    case NOT -> ((Condition) operandField).not();
                };
            }
            case TernaryExpression(var first, var op, var second, var third) -> {
                var firstField = (Field) transformExpression(first, ctx);
                var secondField = transformExpression(second, ctx);
                var thirdField = transformExpression(third, ctx);
                yield switch (op) {
                    case BETWEEN -> firstField.between(secondField, thirdField);
                    case NOT_BETWEEN -> firstField.notBetween(secondField, thirdField);
                };
            }
        };
    }

    private Field<?> resolvePath(Path path, QueryContext ctx) {
        var joinInfo = ctx.resolveJoin(path);

        if (path.target() instanceof BasicAttribute basicAttr) {
            return ctx.resolveField(basicAttr, joinInfo.table());
        }

        return field("unknown");
    }
}
