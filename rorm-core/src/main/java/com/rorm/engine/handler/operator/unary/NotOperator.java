package com.rorm.engine.handler.operator.unary;

import com.rorm.engine.handler.BuiltInUnaryOperatorHandler;
import com.rorm.engine.handler.TransformContext;
import com.rorm.engine.handler.TypeResolutionContext;
import com.rorm.metamodel.DataType;
import com.rorm.query.Expression;
import org.jooq.Condition;
import org.jooq.Field;

public final class NotOperator implements BuiltInUnaryOperatorHandler {

    public static final String NAME = "NOT";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public DataType resolveType(Expression operand, TypeResolutionContext ctx) {
        return new DataType.BooleanType();
    }

    @Override
    public Field<?> transform(Expression operand, TransformContext ctx) {
        return ((Condition) ctx.transform(operand)).not();
    }
}
