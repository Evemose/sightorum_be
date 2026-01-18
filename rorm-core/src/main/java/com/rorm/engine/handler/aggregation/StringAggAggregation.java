package com.rorm.engine.handler.aggregation;

import com.rorm.engine.handler.BuiltInAggregationHandler;
import com.rorm.engine.handler.TransformContext;
import com.rorm.engine.handler.TypeResolutionContext;
import com.rorm.metamodel.DataType;
import com.rorm.query.Expression;
import org.jooq.Field;
import org.jooq.impl.DSL;

import java.util.List;

public final class StringAggAggregation implements BuiltInAggregationHandler {
    public static final String NAME = "STRING_AGG";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public DataType resolveType(List<Expression> args, TypeResolutionContext ctx) {
        return new DataType.StringType();
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Field<?> transform(List<Expression> args, boolean distinct, TransformContext ctx) {
        var fields = ctx.transformAll(args);
        if (fields.length >= 2) {
            Field field0 = fields[0];
            Field field1 = fields[1];
            return DSL.groupConcat(field0).separator(field1.toString());
        }
        Field field0 = fields[0];
        return DSL.groupConcat(field0);
    }
}
