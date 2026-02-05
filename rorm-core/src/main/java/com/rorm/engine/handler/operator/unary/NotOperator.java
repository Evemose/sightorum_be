package com.rorm.engine.handler.operator.unary;

import com.rorm.engine.handler.BuiltInUnaryOperatorHandler;
import com.rorm.engine.handler.TransformContext;
import com.rorm.engine.handler.TypeResolutionContext;
import com.rorm.metamodel.DataType;
import com.rorm.query.Expression;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.impl.DSL;

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
    @SuppressWarnings("unchecked")
    public Field<?> transform(Expression operand, TransformContext ctx) {
        var field = ctx.transform(operand);
        if (field instanceof Field<?> boolField && !(field instanceof Condition)) {
            return DSL.condition((Field<Boolean>) boolField).not();
        }
        return ((Condition) field).not();
    }
}
