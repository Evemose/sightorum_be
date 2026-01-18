package com.rorm.engine.handler.aggregation;

import com.rorm.engine.handler.BuiltInAggregationHandler;
import com.rorm.engine.handler.TransformContext;
import com.rorm.engine.handler.TypeResolutionContext;
import com.rorm.metamodel.DataType;
import com.rorm.query.Expression;
import org.jooq.Field;
import org.jooq.impl.DSL;

import java.util.List;

public final class ArrayAggAggregation implements BuiltInAggregationHandler {
    public static final String NAME = "ARRAY_AGG";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public DataType resolveType(List<Expression> args, TypeResolutionContext ctx) {
        var elementType = ctx.resolveFirstArg(args, null);
        return new DataType.ListType(elementType);
    }

    @Override
    public Field<?> transform(List<Expression> args, boolean distinct, TransformContext ctx) {
        return DSL.arrayAgg(ctx.transform(args.getFirst()));
    }
}
