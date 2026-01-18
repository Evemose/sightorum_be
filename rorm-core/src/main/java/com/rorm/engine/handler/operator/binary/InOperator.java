package com.rorm.engine.handler.operator.binary;

import com.rorm.engine.handler.BuiltInBinaryOperatorHandler;
import com.rorm.engine.handler.TransformContext;
import com.rorm.engine.handler.TypeResolutionContext;
import com.rorm.metamodel.DataType;
import com.rorm.query.Expression;
import org.jooq.Field;
import org.jooq.impl.DSL;

import java.util.Arrays;
import java.util.Collection;

public final class InOperator implements BuiltInBinaryOperatorHandler {

    public static final String NAME = "IN";

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
        var leftField = (Field) ctx.transform(left);

        // Special handling for Literal containing Collection/Array
        if (right instanceof Expression.Literal(var value)) {
            if (value instanceof Collection<?> collection) {
                var values = collection.stream().map(DSL::inline).toArray(Field[]::new);
                return leftField.in(values);
            } else if (value != null && value.getClass().isArray()) {
                var arrayValues = Arrays.stream((Object[]) value).map(DSL::inline).toArray(Field[]::new);
                return leftField.in(arrayValues);
            }
        }

        return leftField.in(ctx.transform(right));
    }
}
