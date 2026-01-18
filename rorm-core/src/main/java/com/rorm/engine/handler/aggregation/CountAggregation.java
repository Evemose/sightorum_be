package com.rorm.engine.handler.aggregation;

import com.rorm.engine.handler.BuiltInAggregationHandler;
import com.rorm.engine.handler.TransformContext;
import com.rorm.engine.handler.TypeResolutionContext;
import com.rorm.metamodel.DataType;
import com.rorm.query.Expression;
import org.jooq.Field;

import java.util.List;

import static org.jooq.impl.DSL.*;

public final class CountAggregation implements BuiltInAggregationHandler {
    public static final String NAME = "COUNT";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public DataType resolveType(List<Expression> args, TypeResolutionContext ctx) {
        return new DataType.NumericType(19, 0);
    }

    @Override
    public Field<?> transform(List<Expression> args, boolean distinct, TransformContext ctx) {
        if (args.isEmpty() || (args.size() == 1 && args.getFirst() instanceof Expression.Literal(
            var val
        ) && "*".equals(val))) {
            return distinct ? countDistinct(asterisk()) : count(asterisk());
        } else {
            var field = ctx.transform(args.getFirst());
            return distinct ? countDistinct(field) : count(field);
        }
    }
}
