package com.rorm.engine;

import com.rorm.engine.handler.HandlerRegistry;
import com.rorm.engine.handler.TransformContext;
import com.rorm.metamodel.AliasedRoot;
import com.rorm.metamodel.BasicAttribute;
import com.rorm.metamodel.CollectionAttribute.BasicElement;
import com.rorm.metamodel.ReferenceAttribute.SameTableColumn;
import com.rorm.metamodel.ReferenceAttribute.JoinTableMapping;
import com.rorm.metamodel.ReferenceAttribute.InverseRootTableColumn;
import com.rorm.metamodel.SingularReferenceAttribute;
import com.rorm.query.*;
import com.rorm.query.Expression.*;
import com.rorm.query.Selector.MultiExprSelector;
import com.rorm.query.Selector.RootSelector;
import com.rorm.query.Selector.SingleExprSelector;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.jooq.AggregateFilterStep;
import org.jooq.Field;
import org.jooq.QuantifiedSelect;
import org.jooq.Record1;
import org.jooq.Select;
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
    public Select<?> transformAsSelect(Expression expression) {
        if (expression instanceof Subquery(var query) && subqueryTransformer != null) {
            return subqueryTransformer.transformAsSelect(query);
        }
        return null;
    }

    @Override
    public Field<?> transform(Expression expr) {
        return switch (expr) {
            case Path path -> resolvePath(path);
            case Literal(var value) -> inline(value);
            case FunctionCall(var name, var args) -> transformFunctionCall(name, args);
            case Aggregation agg -> transformAggregation(agg);
            case WindowFunction(var name, var args, var spec) -> transformWindowFunction(name, args, spec);
            case BinaryExpression(_, _, var right) when right instanceof QuantifiedComparison qc ->
                transformQuantifiedComparison(qc);
            case BinaryExpression(var left, _, _) when left instanceof QuantifiedComparison qc ->
                transformQuantifiedComparison(qc);
            case BinaryExpression(var left, var op, var right) -> transformBinary(left, op, right);
            case QuantifiedComparison quant -> transformQuantifiedComparison(quant);
            case UnaryExpression(var op, var operand) -> transformUnary(op, operand);
            case TernaryExpression(var first, var op, var second, var third) ->
                transformTernary(first, op, second, third);
            case CaseExpression caseExpr -> transformCase(caseExpr);
            case Subquery(var query) -> subqueryTransformer.transform(query);
        };
    }

    Field<?>[] renderSelectorFields(Selector selector, QueryContext ctx) {
        return switch (selector) {
            case RootSelector(var root, _) -> root.attributes().stream()
                .filter(BasicAttribute.class::isInstance)
                .map(BasicAttribute.class::cast)
                .map(attr -> ctx.resolveField(attr, ctx.rootTable()))
                .toArray(Field[]::new);
            case SingleExprSelector(var e, _, var alias) -> {
                var f = alias != null ? transform(e).as(alias) : transform(e);
                yield new Field[]{f};
            }
            case MultiExprSelector(var exprs, _) -> exprs.stream()
                .map(ae -> ae.alias() != null
                    ? transform(ae.expression()).as(ae.alias())
                    : transform(ae.expression()))
                .toArray(Field[]::new);
        };
    }

    private Field<?> resolvePath(Path path) {
        return resolvePathInContext(path, ctx().findOwner(path));
    }

    private Field<?> resolvePathInContext(Path path, QueryContext context) {
        var directReferenceId = resolveDirectReferenceId(path, context);
        if (directReferenceId != null) {
            return directReferenceId;
        }
        var effectivePath = PathUtils.extendReferenceIfNeeded(path);
        var joinInfo = context.resolveJoin(effectivePath);

        return switch (effectivePath.target()) {
            case BasicAttribute attr -> context.resolveField(attr, joinInfo.table());
            case BasicElement(var loc, _) -> field(name(joinInfo.table().getName(), loc.column()));
            case AliasedRoot _ -> throw new IllegalArgumentException(
                "Cannot select a JoinedRoot directly; select an attribute from it instead");
            default -> throw new UnsupportedOperationException(
                "Unsupported path target: " + effectivePath.target().getClass().getSimpleName());
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

    @SuppressWarnings({"rawtypes"})
    private Field<?> transformAggregation(Expression.Aggregation agg) {
        var handler = handlerRegistry.getAggregation(agg.functionName());
        var field = handler.transform(agg.arguments(), agg.distinct(), this);
        if (agg.filterWhere() != null && field instanceof AggregateFilterStep filterStep) {
            return (Field<?>) filterStep.filterWhere(toCondition(transform(agg.filterWhere())));
        }
        return field;
    }

    private Field<?> transformWindowFunction(String name, List<Expression> args, WindowSpec spec) {
        var handler = handlerRegistry.getWindowFunction(name);
        return handler.transform(args, spec, this);
    }

    private Field<?> transformBinary(Expression left, String op, Expression right) {
        var handler = handlerRegistry.getBinaryOperator(op);
        return handler.transform(left, right, this);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Field<?> transformQuantifiedComparison(QuantifiedComparison quant) {
        var leftField = (Field) transform(quant.left());
        var select = transformAsSelect(quant.subquery());
        if (select == null) {
            throw new IllegalArgumentException("Failed to transform quantified subquery");
        }

        var quantified = (QuantifiedSelect<Record1<?>>) switch (quant.quantifier()) {
            case ANY -> any(select);
            case ALL -> all(select);
        };

        return switch (quant.comparison()) {
            case EQUALS -> leftField.eq(quantified);
            case GREATER_THAN -> leftField.gt(quantified);
            case GREATER_THAN_OR_EQUAL -> leftField.ge(quantified);
            case LESS_THAN -> leftField.lt(quantified);
            case LESS_THAN_OR_EQUAL -> leftField.le(quantified);
            default -> throw new IllegalStateException(
                "Unsupported quantified comparison operator: " + quant.comparison()
            );
        };
    }

    private Field<?> transformUnary(String op, Expression operand) {
        var handler = handlerRegistry.getUnaryOperator(op);
        return handler.transform(operand, this);
    }

    private Field<?> transformTernary(Expression first, String op, Expression second, Expression third) {
        var handler = handlerRegistry.getTernaryOperator(op);
        return handler.transform(first, second, third, this);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Field<?> transformCase(CaseExpression caseExpr) {
        var whens = caseExpr.whens();
        var first = whens.getFirst();
        var step = when(
            toCondition(transform(first.condition())),
            (Field) transform(first.result())
        );
        for (int i = 1; i < whens.size(); i++) {
            var w = whens.get(i);
            step = step.when(
                toCondition(transform(w.condition())),
                (Field) transform(w.result())
            );
        }
        if (caseExpr.elseExpr() != null) {
            return step.otherwise((Field) transform(caseExpr.elseExpr()));
        }
        return step;
    }

    @SuppressWarnings("unchecked")
    private org.jooq.Condition toCondition(Field<?> field) {
        if (field instanceof org.jooq.Condition cond) {
            return cond;
        }
        return condition((Field<Boolean>) field);
    }

    private Field<?> resolveDirectReferenceId(Path path, QueryContext context) {
        if (!PathUtils.isDirectReferenceId(path)) {
            return null;
        }
        var ref = (SingularReferenceAttribute) path.parent().target();
        var ownerPath = path.parent().parent();
        var ownerTable = ownerPath == null
            ? context.rootTable()
            : context.resolveJoin(ownerPath).table();

        return switch (ref.mappingStrategy()) {
            case SameTableColumn(var columnName) -> field(name(ownerTable.getName(), columnName));
            case JoinTableMapping(var joinColumnLocation, _) ->
                field(name(ownerTable.getName(), joinColumnLocation.column()));
            case InverseRootTableColumn _ -> null;
        };
    }
}
