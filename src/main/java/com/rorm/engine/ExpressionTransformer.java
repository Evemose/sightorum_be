package com.rorm.engine;

import com.rorm.metamodel.AttributeLocation;
import com.rorm.metamodel.BasicAttribute;
import com.rorm.metamodel.CollectionAttribute.BasicElement;
import com.rorm.metamodel.ReferenceAttribute;
import com.rorm.query.Expression;
import com.rorm.query.Expression.*;
import com.rorm.query.Operator.*;
import com.rorm.query.OuterRef;
import com.rorm.query.Path;
import com.rorm.query.Subquery;
import com.rorm.query.WindowSpec;
import lombok.AccessLevel;
import lombok.Setter;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.SortField;
import org.jooq.WindowOverStep;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

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
            case WindowFunction(var name, var args, var spec) -> transformWindowFunction(name, args, spec);
            case BinaryExpression(var left, var op, var right) -> transformBinary(left, op, right);
            case UnaryExpression(var op, var operand) -> transformUnary(op, operand);
            case TernaryExpression(var first, var op, var second, var third) -> transformTernary(first, op, second, third);
            case Subquery(var query) -> subqueryTransformer.transform(query);
            case OuterRef(var depth, var path) -> resolveOuterRef(depth, path);
        };
    }

    private Field<?> resolvePath(Path path) {
        var effectivePath = extendReferencePathIfNeeded(path);
        var joinInfo = ctx().resolveJoin(effectivePath);

        return switch (effectivePath.target()) {
            case BasicAttribute attr -> ctx().resolveField(attr, joinInfo.table());
            case BasicElement(var loc) -> field(name(joinInfo.table().getName(), loc.column()));
            default -> throw new UnsupportedOperationException(
                "Unsupported path target: " + effectivePath.target().getClass().getSimpleName());
        };
    }

    Path extendReferencePathIfNeeded(Path path) {
        if (path.target() instanceof ReferenceAttribute refAttr) {
            var targetRoot = refAttr.targetRoot();
            var syntheticId = new BasicAttribute("id", new AttributeLocation(targetRoot.primaryTableName(), "id"));
            return new Path(syntheticId, path);
        }
        return path;
    }

    private Field<?> resolveOuterRef(int depth, Path path) {
        var outerCtx = ctx().ancestor(depth);
        var effectivePath = extendReferencePathIfNeeded(path);
        var joinInfo = outerCtx.resolveJoin(effectivePath);

        return switch (effectivePath.target()) {
            case BasicAttribute attr -> outerCtx.resolveField(attr, joinInfo.table());
            case BasicElement(var loc) -> field(name(joinInfo.table().getName(), loc.column()));
            default -> throw new UnsupportedOperationException(
                "Unsupported outer ref path target: " + effectivePath.target().getClass().getSimpleName());
        };
    }

    private Field<?> transformFunctionCall(String name, List<Expression> args) {
        var argFields = args.stream().map(this::transform).toArray(Field[]::new);
        return function(name, Object.class, argFields);
    }

    @SuppressWarnings("unchecked")
    private Field<?> transformWindowFunction(String name, List<Expression> args, WindowSpec spec) {
        var argFields = args.stream().map(this::transform).toArray(Field[]::new);
        var partitionFields = spec.partitionBy() != null && !spec.partitionBy().isEmpty()
            ? spec.partitionBy().stream().map(this::transform).toArray(Field[]::new)
            : null;
        var orderFields = spec.orderBy() != null && !spec.orderBy().isEmpty()
            ? spec.orderBy().stream().map(ob -> {
                var f = transform(ob.expression());
                return ob.ascending() ? f.asc() : f.desc();
            }).toArray(SortField[]::new)
            : null;

        return switch (name.toLowerCase()) {
            case "count" -> applyWindowSpec(count(argFields.length > 0 ? argFields[0] : asterisk()), partitionFields, orderFields);
            case "sum" -> applyWindowSpec(sum(argFields[0]), partitionFields, orderFields);
            case "avg" -> applyWindowSpec(avg(argFields[0]), partitionFields, orderFields);
            case "min" -> applyWindowSpec(min(argFields[0]), partitionFields, orderFields);
            case "max" -> applyWindowSpec(max(argFields[0]), partitionFields, orderFields);
            case "row_number" -> applyWindowSpec(rowNumber(), partitionFields, orderFields);
            case "rank" -> applyWindowSpec(rank(), partitionFields, orderFields);
            case "dense_rank" -> applyWindowSpec(denseRank(), partitionFields, orderFields);
            default -> throw new UnsupportedOperationException("Unsupported window function: " + name);
        };
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

    @SuppressWarnings({"rawtypes"})
    private Field<?> transformBinary(Expression left, BinaryOperator op, Expression right) {
        var leftField = (Field) transform(left);
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
