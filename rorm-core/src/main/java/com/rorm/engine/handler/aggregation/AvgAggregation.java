package com.rorm.engine.handler.aggregation;

import com.rorm.engine.handler.BuiltInAggregationHandler;
import com.rorm.engine.handler.TransformContext;
import com.rorm.engine.handler.TypeResolutionContext;
import com.rorm.metamodel.DataType;
import com.rorm.query.Expression;
import org.jooq.Field;

import java.util.List;

import static org.jooq.impl.DSL.avg;
import static org.jooq.impl.DSL.avgDistinct;

public final class AvgAggregation implements BuiltInAggregationHandler {
    public static final String NAME = "AVG";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public DataType resolveType(List<Expression> args, TypeResolutionContext ctx) {
        var argType = ctx.resolveFirstArg(args, null);
        if (argType instanceof DataType.NumericType(int precision, int scale)) {
            return new DataType.NumericType(precision, Math.max(scale, 6));
        }
        return new DataType.NumericType(19, 6);
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Field<?> transform(List<Expression> args, boolean distinct, TransformContext ctx) {
        var field = (Field) ctx.transform(args.getFirst());
        return distinct ? avgDistinct(field) : avg(field);
    }
}
