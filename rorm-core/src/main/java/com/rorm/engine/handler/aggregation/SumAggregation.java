package com.rorm.engine.handler.aggregation;

import com.rorm.engine.handler.BuiltInAggregationHandler;
import com.rorm.engine.handler.TransformContext;
import com.rorm.engine.handler.TypeResolutionContext;
import com.rorm.metamodel.DataType;
import com.rorm.query.Expression;
import org.jooq.Field;

import java.util.List;

import static org.jooq.impl.DSL.sum;
import static org.jooq.impl.DSL.sumDistinct;

public final class SumAggregation implements BuiltInAggregationHandler {
    public static final String NAME = "SUM";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public DataType resolveType(List<Expression> args, TypeResolutionContext ctx) {
        var argType = ctx.resolveFirstArg(args, null);
        if (argType instanceof DataType.NumericType(int precision, int scale)) {
            return new DataType.NumericType(precision + 4, scale);
        }
        return new DataType.NumericType(19, 6);
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Field<?> transform(List<Expression> args, boolean distinct, TransformContext ctx) {
        var field = (Field) ctx.transform(args.getFirst());
        return distinct ? sumDistinct(field) : sum(field);
    }
}
