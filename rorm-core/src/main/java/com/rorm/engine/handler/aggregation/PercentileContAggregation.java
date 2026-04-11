package com.rorm.engine.handler.aggregation;

import com.rorm.engine.handler.BuiltInAggregationHandler;
import com.rorm.engine.handler.TransformContext;
import com.rorm.engine.handler.TypeResolutionContext;
import com.rorm.metamodel.DataType;
import com.rorm.query.Expression;
import org.jooq.Field;
import org.jooq.impl.DSL;

import java.util.List;

public final class PercentileContAggregation implements BuiltInAggregationHandler {
    public static final String NAME = "PERCENTILE_CONT";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public DataType resolveType(List<Expression> args, TypeResolutionContext ctx) {
        var orderType = ctx.resolveSecondArg(args, new DataType.NumericType(19, 10));
        if (orderType instanceof DataType.NumericType(int precision, int scale)) {
            return new DataType.NumericType(precision, Math.max(scale, 10));
        }
        return orderType;
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Field<?> transform(List<Expression> args, boolean distinct, TransformContext ctx) {
        if (args.size() != 2) {
            throw new IllegalArgumentException(
                "PERCENTILE_CONT requires exactly 2 arguments: [fraction, orderExpression]");
        }
        var fraction = extractFraction(args.get(0));
        Field orderField = ctx.transform(args.get(1));
        return DSL.percentileCont(fraction).withinGroupOrderBy(orderField);
    }

    private static Number extractFraction(Expression fractionExpr) {
        if (fractionExpr instanceof Expression.Literal(Object value)) {
            if (value instanceof Number num) {
                var asDouble = num.doubleValue();
                if (asDouble < 0.0 || asDouble > 1.0) {
                    throw new IllegalArgumentException(
                        "PERCENTILE_CONT fraction must be in [0, 1], got: " + asDouble);
                }
                return num;
            }
            throw new IllegalArgumentException(
                "PERCENTILE_CONT fraction literal must be numeric, got: " + value);
        }
        throw new IllegalArgumentException(
            "PERCENTILE_CONT fraction must be a numeric literal, got: " + fractionExpr);
    }
}
