package com.rorm.engine.handler.operator.binary;

import com.rorm.engine.handler.BooleanFieldUtils;
import com.rorm.engine.handler.BuiltInBinaryOperatorHandler;
import com.rorm.engine.handler.TransformContext;
import com.rorm.engine.handler.TypeResolutionContext;
import com.rorm.metamodel.DataType;
import com.rorm.query.Expression;
import org.jooq.Field;

public final class OrOperator implements BuiltInBinaryOperatorHandler {

    public static final String NAME = "OR";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public DataType resolveType(Expression left, Expression right, TypeResolutionContext ctx) {
        return new DataType.BooleanType();
    }

    @Override
    public Field<?> transform(Expression left, Expression right, TransformContext ctx) {
        var leftField = ctx.transform(left);
        var rightField = ctx.transform(right);
        return BooleanFieldUtils.asCondition(leftField).or(BooleanFieldUtils.asCondition(rightField));
    }
}
