package com.rorm.engine.handler.aggregation;

import com.rorm.engine.handler.BuiltInAggregationHandler;
import com.rorm.engine.handler.TransformContext;
import com.rorm.engine.handler.TypeResolutionContext;
import com.rorm.metamodel.DataType;
import com.rorm.query.Expression;
import org.jooq.Field;

import java.util.List;

import static org.jooq.impl.DSL.max;
import static org.jooq.impl.DSL.maxDistinct;

public final class MaxAggregation implements BuiltInAggregationHandler {
    public static final String NAME = "MAX";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public DataType resolveType(List<Expression> args, TypeResolutionContext ctx) {
        return ctx.resolveFirstArg(args, null);
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Field<?> transform(List<Expression> args, boolean distinct, TransformContext ctx) {
        var field = (Field) ctx.transform(args.getFirst());
        return distinct ? maxDistinct(field) : max(field);
    }
}
