package com.rorm.engine;

import com.rorm.engine.handler.HandlerRegistry;
import com.rorm.engine.handler.TransformContext;
import com.rorm.metamodel.AliasedRoot;
import com.rorm.metamodel.BasicAttribute;
import com.rorm.metamodel.CollectionAttribute.BasicElement;
import com.rorm.metamodel.ReferenceAttribute;
import com.rorm.query.*;
import com.rorm.query.Expression.*;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.jooq.Field;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Supplier;

import static org.jooq.impl.DSL.*;

@Component
@RequiredArgsConstructor
class ExpressionTransformer implements TransformContext {

    private final HandlerRegistry handlerRegistry;
    private final ScopedValue<QueryContext> ctxScope = ScopedValue.newInstance();

    @Setter(AccessLevel.PACKAGE)
    private SubqueryTransformer subqueryTransformer;

    <T> T withContext(QueryContext ctx, Supplier<T> action) {
        return ScopedValue.where(ctxScope, ctx).call(action::get);
    }

    QueryContext ctx() {
        return ctxScope.get();
    }

    @Override
    public Field<?> transform(Expression expr) {
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
            case AliasedRoot _ -> throw new IllegalArgumentException(
                "Cannot select a JoinedRoot directly; select an attribute from it instead");
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
            case AliasedRoot _ -> throw new IllegalArgumentException(
                "Cannot select a JoinedRoot directly in OuterRef; select an attribute from it instead");
            default -> throw new UnsupportedOperationException(
                "Unsupported outer ref path target: " + effectivePath.target().getClass().getSimpleName());
        };
    }

    private Field<?> transformFunctionCall(String name, List<Expression> args) {
        var handler = handlerRegistry.findFunction(name);
        if (handler.isPresent()) {
            return handler.get().transform(args, this);
        }
        // Fallback for unknown functions
        var argFields = args.stream().map(this::transform).toArray(Field[]::new);
        return function(name, Object.class, argFields);
    }

    private Field<?> transformAggregation(String name, List<Expression> args, boolean distinct) {
        var handler = handlerRegistry.getAggregation(name);
        return handler.transform(args, distinct, this);
    }

    private Field<?> transformWindowFunction(String name, List<Expression> args, WindowSpec spec) {
        var handler = handlerRegistry.getWindowFunction(name);
        return handler.transform(args, spec, this);
    }

    private Field<?> transformBinary(Expression left, String op, Expression right) {
        var handler = handlerRegistry.getBinaryOperator(op);
        return handler.transform(left, right, this);
    }

    private Field<?> transformUnary(String op, Expression operand) {
        var handler = handlerRegistry.getUnaryOperator(op);
        return handler.transform(operand, this);
    }

    private Field<?> transformTernary(Expression first, String op, Expression second, Expression third) {
        var handler = handlerRegistry.getTernaryOperator(op);
        return handler.transform(first, second, third, this);
    }
}
