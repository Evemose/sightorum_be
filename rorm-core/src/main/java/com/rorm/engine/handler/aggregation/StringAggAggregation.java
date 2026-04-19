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
        var value = ctx.transform(args.getFirst());
        var delimiter = args.size() >= 2 ? ctx.transform(args.get(1)) : DSL.inline(",");
        if (distinct) {
            return DSL.field("string_agg(distinct {0}, {1})", String.class, value, delimiter);
        }
        return DSL.field("string_agg({0}, {1})", String.class, value, delimiter);
    }
}
