package com.rorm.engine.handler.operator.binary;

import com.rorm.engine.handler.BuiltInBinaryOperatorHandler;
import com.rorm.engine.handler.TransformContext;
import com.rorm.engine.handler.TypeResolutionContext;
import com.rorm.metamodel.DataType;
import com.rorm.query.Expression;
import org.jooq.Field;

public final class SubtractOperator implements BuiltInBinaryOperatorHandler {

    public static final String NAME = "SUBTRACT";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public DataType resolveType(Expression left, Expression right, TypeResolutionContext ctx) {
        var leftType = ctx.resolve(left);
        var rightType = ctx.resolve(right);
        if (leftType instanceof DataType.DateType && rightType instanceof DataType.DateType) {
            // PostgreSQL date - date returns integer day count.
            return new DataType.NumericType(19, 0);
        }
        if ((leftType instanceof DataType.DateTimeType || leftType instanceof DataType.DateType)
            && (rightType instanceof DataType.DateTimeType || rightType instanceof DataType.DateType)) {
            return new DataType.IntervalType();
        }
        if ((leftType instanceof DataType.DateType || leftType instanceof DataType.DateTimeType)
            && rightType instanceof DataType.IntervalType) {
            return leftType;
        }
        return ctx.promoteNumeric(leftType, rightType);
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Field<?> transform(Expression left, Expression right, TransformContext ctx) {
        return ((Field) ctx.transform(left)).sub(ctx.transform(right));
    }
}
