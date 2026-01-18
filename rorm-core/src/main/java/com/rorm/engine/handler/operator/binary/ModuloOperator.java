package com.rorm.engine.handler.operator.binary;

import com.rorm.engine.handler.BuiltInBinaryOperatorHandler;
import com.rorm.engine.handler.TransformContext;
import com.rorm.engine.handler.TypeResolutionContext;
import com.rorm.metamodel.DataType;
import com.rorm.query.Expression;
import org.jooq.Field;

public final class ModuloOperator implements BuiltInBinaryOperatorHandler {

    public static final String NAME = "MODULO";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public DataType resolveType(Expression left, Expression right, TypeResolutionContext ctx) {
        var leftType = ctx.resolve(left);
        var rightType = ctx.resolve(right);
        return ctx.promoteNumeric(leftType, rightType);
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Field<?> transform(Expression left, Expression right, TransformContext ctx) {
        return ((Field) ctx.transform(left)).mod(ctx.transform(right));
    }
}
