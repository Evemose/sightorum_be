package com.rorm.engine;

import com.rorm.metamodel.BasicAttribute;
import com.rorm.metamodel.CollectionAttribute.BasicElement;
import com.rorm.metamodel.ReferenceAttribute;
import com.rorm.query.*;
import com.rorm.query.Expression.*;
import com.rorm.query.Operator.BinaryOperator;
import com.rorm.query.Operator.TernaryOperator;
import com.rorm.query.Operator.UnaryOperator;
import lombok.AccessLevel;
import lombok.Setter;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.SortField;
import org.jooq.WindowOverStep;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

import static org.jooq.impl.DSL.*;

@Component
class ExpressionTransformer {

    private final ScopedValue<QueryContext> ctxScope = ScopedValue.newInstance();

    @Setter(value = AccessLevel.PACKAGE, onMethod_ = {@Lazy, @Autowired})
    private SubqueryTransformer subqueryTransformer;

    <T> T withContext(QueryContext ctx, Supplier<T> action) {
        return ScopedValue.where(ctxScope, ctx).call(action::get);
    }

    QueryContext ctx() {
        return ctxScope.get();
    }

    Field<?> transform(Expression expr) {
        return switch (expr) {
            case Path path -> resolvePath(path);
            case Literal(var value) -> inline(value);
            case FunctionCall(var name, var args) -> transformFunctionCall(name, args);
            case Aggregation(var name, var args, var distinct) -> transformAggregation(name, args, distinct);
            case WindowFunction(var name, var args, var spec) -> transformWindowFunction(name, args, spec);
            case BinaryExpression(var left, var op, var right) -> transformBinary(left, op, right);
            case UnaryExpression(var op, var operand) -> transformUnary(op, operand);
            case TernaryExpression(var first, var op, var second, var third) ->
                transformTernary(first, op, second, third);
            case Subquery(var query) -> subqueryTransformer.transform(query);
            case OuterRef(var depth, var path) -> resolveOuterRef(depth, path);
        };
    }

    private Field<?> resolvePath(Path path) {
        var effectivePath = extendReferencePathIfNeeded(path);
        var joinInfo = ctx().resolveJoin(effectivePath);

        return switch (effectivePath.target()) {
            case BasicAttribute attr -> ctx().resolveField(attr, joinInfo.table());
            case BasicElement(var loc, _) -> field(name(joinInfo.table().getName(), loc.column()));
            default -> throw new UnsupportedOperationException(
                "Unsupported path target: " + effectivePath.target().getClass().getSimpleName());
        };
    }

    Path extendReferencePathIfNeeded(Path path) {
        if (path.target() instanceof ReferenceAttribute refAttr) {
            var targetRoot = refAttr.targetRoot();
            return new Path(targetRoot.idDescriptor().idAttribute(), path);
        }
        return path;
    }

    private Field<?> resolveOuterRef(int depth, Path path) {
        var outerCtx = ctx().ancestor(depth);
        var effectivePath = extendReferencePathIfNeeded(path);
        var joinInfo = outerCtx.resolveJoin(effectivePath);

        return switch (effectivePath.target()) {
            case BasicAttribute attr -> outerCtx.resolveField(attr, joinInfo.table());
            case BasicElement(var loc, _) -> field(name(joinInfo.table().getName(), loc.column()));
            default -> throw new UnsupportedOperationException(
                "Unsupported outer ref path target: " + effectivePath.target().getClass().getSimpleName());
        };
    }

    private Field<?> transformFunctionCall(String name, List<Expression> args) {
        var argFields = args.stream().map(this::transform).toArray(Field[]::new);
        return function(name, Object.class, argFields);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Field<?> transformAggregation(String name, List<Expression> args, boolean distinct) {
        return switch (name.toUpperCase()) {
            case "COUNT" -> {
                if (args.isEmpty() || (args.size() == 1 && args.getFirst() instanceof Literal(
                    var val
                ) && "*".equals(val))) {
                    yield distinct ? countDistinct(asterisk()) : count(asterisk());
                } else {
                    var field = transform(args.getFirst());
                    yield distinct ? countDistinct(field) : count(field);
                }
            }
            case "SUM" -> {
                var field = (Field) transform(args.getFirst());
                yield distinct ? sumDistinct(field) : sum(field);
            }
            case "AVG" -> {
                var field = (Field) transform(args.getFirst());
                yield distinct ? avgDistinct(field) : avg(field);
            }
            case "MIN" -> {
                var field = (Field) transform(args.getFirst());
                yield distinct ? minDistinct(field) : min(field);
            }
            case "MAX" -> {
                var field = (Field) transform(args.getFirst());
                yield distinct ? maxDistinct(field) : max(field);
            }
            default -> throw new UnsupportedOperationException("Unsupported aggregation function: " + name);
        };
    }

    @SuppressWarnings("unchecked")
    private Field<?> transformWindowFunction(String name, List<Expression> args, WindowSpec spec) {
        var argFields = args.stream().map(this::transform).toArray(Field[]::new);
        var partitionFields = transformPartitionFields(spec);
        var orderFields = transformOrderFields(spec);

        return switch (name.toLowerCase()) {
            case "count" ->
                applyWindowSpec(count(argFields.length > 0 ? argFields[0] : asterisk()), partitionFields, orderFields);
            case "sum" -> applyWindowSpec(sum(argFields[0]), partitionFields, orderFields);
            case "avg" -> applyWindowSpec(avg(argFields[0]), partitionFields, orderFields);
            case "min" -> applyWindowSpec(min(argFields[0]), partitionFields, orderFields);
            case "max" -> applyWindowSpec(max(argFields[0]), partitionFields, orderFields);
            case "row_number" -> applyWindowSpec(rowNumber(), partitionFields, orderFields);
            case "rank" -> applyWindowSpec(rank(), partitionFields, orderFields);
            case "dense_rank" -> applyWindowSpec(denseRank(), partitionFields, orderFields);
            case "lag" -> transformLagFunction(args, argFields, partitionFields, orderFields);
            case "lead" -> transformLeadFunction(args, argFields, partitionFields, orderFields);
            case "first_value" -> applyWindowSpec(firstValue(argFields[0]), partitionFields, orderFields);
            case "last_value" -> applyWindowSpec(lastValue(argFields[0]), partitionFields, orderFields);
            case "nth_value" -> {
                if (argFields.length >= 2) {
                    int n = extractIntValue(args.get(1));
                    yield applyWindowSpec(nthValue(argFields[0], n), partitionFields, orderFields);
                }
                throw new IllegalArgumentException("NTH_VALUE requires 2 arguments");
            }
            case "ntile" -> {
                if (argFields.length >= 1) {
                    int buckets = extractIntValue(args.getFirst());
                    yield applyWindowSpec(ntile(buckets), partitionFields, orderFields);
                }
                throw new IllegalArgumentException("NTILE requires 1 argument");
            }
            case "percent_rank" -> applyWindowSpec(percentRank(), partitionFields, orderFields);
            case "cume_dist" -> applyWindowSpec(cumeDist(), partitionFields, orderFields);
            default -> throw new UnsupportedOperationException("Unsupported window function: " + name);
        };
    }

    private Field<?>[] transformPartitionFields(WindowSpec spec) {
        return spec.partitionBy() != null && !spec.partitionBy().isEmpty()
            ? spec.partitionBy().stream().map(this::transform).toArray(Field[]::new)
            : null;
    }

    private SortField<?>[] transformOrderFields(WindowSpec spec) {
        return spec.orderBy() != null && !spec.orderBy().isEmpty() ?
            spec.orderBy().stream().map(ob -> {
                var f = transform(ob.expression());
                return ob.ascending() ? f.asc() : f.desc();
            }).toArray(SortField[]::new)
            : null;
    }

    @SuppressWarnings("unchecked")
    private Field<?> transformLagFunction(List<Expression> args, Field<?>[] argFields, Field<?>[] partitionFields, SortField<?>[] orderFields) {
        if (argFields.length == 1) {
            return applyWindowSpec(lag(argFields[0]), partitionFields, orderFields);
        } else if (argFields.length == 2) {
            int offset = extractIntValue(args.get(1));
            return applyWindowSpec(lag(argFields[0], offset), partitionFields, orderFields);
        } else if (argFields.length == 3) {
            int offset = extractIntValue(args.get(1));
            var defaultValue = (Field<Void>) argFields[2];
            return applyWindowSpec(
                // this cast is needed to select the correct overload
                lag((Field<Void>) argFields[0], inline(offset), defaultValue),
                partitionFields,
                orderFields
            );
        } else {
            throw new IllegalArgumentException("LAG requires 1 - 3 arguments");
        }
    }

    @SuppressWarnings("unchecked")
    private Field<?> transformLeadFunction(List<Expression> args, Field<?>[] argFields, Field<?>[] partitionFields, SortField<?>[] orderFields) {
        if (argFields.length == 1) {
            return applyWindowSpec(lead(argFields[0]), partitionFields, orderFields);
        } else if (argFields.length == 2) {
            int offset = extractIntValue(args.get(1));
            return applyWindowSpec(lead(argFields[0], offset), partitionFields, orderFields);
        } else if (argFields.length == 3) {
            int offset = extractIntValue(args.get(1));
            var defaultValue = (Field<Void>) argFields[2];
            return applyWindowSpec(
                // this cast is needed to select the correct overload
                lead((Field<Void>) argFields[0], inline(offset), defaultValue),
                partitionFields,
                orderFields
            );
        } else {
            throw new IllegalArgumentException("LEAD requires 1 - 3 arguments");
        }
    }

    private <T> Field<?> applyWindowSpec(WindowOverStep<T> func, Field<?>[] partition, SortField<?>[] order) {
        if (partition != null && order != null) {
            return func.over().partitionBy(partition).orderBy(order);
        } else if (partition != null) {
            return func.over().partitionBy(partition);
        } else if (order != null) {
            return func.over().orderBy(order);
        }
        return func.over();
    }

    private int extractIntValue(Expression expr) {
        if (expr instanceof Literal(Number value)) {
            return value.intValue();
        }
        throw new IllegalArgumentException("Expected integer literal, got: " + expr);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Field<?> transformBinary(Expression left, BinaryOperator op, Expression right) {
        var leftField = (Field) transform(left);

        // Special handling for IN/NOT_IN operators with Literal containing Collection/Array
        if ((op == BinaryOperator.IN || op == BinaryOperator.NOT_IN) && right instanceof Literal(var value)) {
            if (value instanceof Collection<?> collection) {
                var values = collection.stream().map(DSL::inline).toArray(Field[]::new);
                return op == BinaryOperator.IN ? leftField.in(values) : leftField.notIn(values);
            } else if (value != null && value.getClass().isArray()) {
                var arrayValues = java.util.Arrays.stream((Object[]) value).map(DSL::inline).toArray(Field[]::new);
                return op == BinaryOperator.IN ? leftField.in(arrayValues) : leftField.notIn(arrayValues);
            }
        }

        var rightField = (Field) transform(right);
        return applyBinaryOperator(leftField, op, rightField);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Field<?> applyBinaryOperator(Field left, BinaryOperator op, Field right) {
        return switch (op) {
            case EQUALS -> left.eq(right);
            case NOT_EQUALS -> left.ne(right);
            case GREATER_THAN -> left.gt(right);
            case GREATER_THAN_OR_EQUAL -> left.ge(right);
            case LESS_THAN -> left.lt(right);
            case LESS_THAN_OR_EQUAL -> left.le(right);
            case LIKE -> left.like(right);
            case NOT_LIKE -> left.notLike(right);
            case IN -> left.in(right);
            case NOT_IN -> left.notIn(right);
            case ADD -> left.add(right);
            case SUBTRACT -> left.sub(right);
            case MULTIPLY -> left.mul(right);
            case DIVIDE -> left.div(right);
            case MODULO -> left.mod(right);
            case AND -> ((Condition) left).and((Condition) right);
            case OR -> ((Condition) left).or((Condition) right);
        };
    }

    @SuppressWarnings({"rawtypes"})
    private Field<?> transformUnary(UnaryOperator op, Expression operand) {
        var field = (Field) transform(operand);
        return switch (op) {
            case IS_NULL -> field.isNull();
            case IS_NOT_NULL -> field.isNotNull();
            case IS_TRUE -> field.isTrue();
            case IS_FALSE -> field.isFalse();
            case NEGATE -> field.neg();
            case NOT -> ((Condition) field).not();
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Field<?> transformTernary(Expression first, TernaryOperator op, Expression second, Expression third) {
        var firstField = (Field) transform(first);
        var secondField = transform(second);
        var thirdField = transform(third);
        return switch (op) {
            case BETWEEN -> firstField.between(secondField, thirdField);
            case NOT_BETWEEN -> firstField.notBetween(secondField, thirdField);
        };
    }
}
