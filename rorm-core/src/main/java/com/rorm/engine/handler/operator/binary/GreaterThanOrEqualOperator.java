package com.rorm.engine.handler.operator.binary;

import com.rorm.engine.handler.BuiltInBinaryOperatorHandler;
import com.rorm.engine.handler.TransformContext;
import com.rorm.engine.handler.TypeResolutionContext;
import com.rorm.metamodel.DataType;
import com.rorm.query.Expression;
import org.jooq.Field;

public final class GreaterThanOrEqualOperator implements BuiltInBinaryOperatorHandler {

    public static final String NAME = "GREATER_THAN_OR_EQUAL";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public DataType resolveType(Expression left, Expression right, TypeResolutionContext ctx) {
        return new DataType.BooleanType();
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Field<?> transform(Expression left, Expression right, TransformContext ctx) {
        return ((Field) ctx.transform(left)).ge(ctx.transform(right));
    }
}
