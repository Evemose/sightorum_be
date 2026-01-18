package com.rorm.engine.handler.operator.ternary;

import com.rorm.engine.handler.BuiltInTernaryOperatorHandler;
import com.rorm.engine.handler.TransformContext;
import com.rorm.engine.handler.TypeResolutionContext;
import com.rorm.metamodel.DataType;
import com.rorm.query.Expression;
import org.jooq.Field;

public final class BetweenOperator implements BuiltInTernaryOperatorHandler {

    public static final String NAME = "BETWEEN";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public DataType resolveType(Expression first, Expression second, Expression third, TypeResolutionContext ctx) {
        return new DataType.BooleanType();
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Field<?> transform(Expression first, Expression second, Expression third, TransformContext ctx) {
        var firstField = (Field) ctx.transform(first);
        var secondField = ctx.transform(second);
        var thirdField = ctx.transform(third);
        return firstField.between(secondField, thirdField);
    }
}
