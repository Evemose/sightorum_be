package com.rorm.engine.handler.function;

import com.rorm.engine.handler.BuiltInFunctionHandler;
import com.rorm.engine.handler.TransformContext;
import com.rorm.engine.handler.TypeResolutionContext;
import com.rorm.metamodel.DataType;
import com.rorm.query.Expression;
import org.jooq.Field;
import org.jooq.impl.DSL;

import java.util.List;

public final class DateTruncFunction implements BuiltInFunctionHandler {
    public static final String NAME = "DATE_TRUNC";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public DataType resolveType(List<Expression> args, TypeResolutionContext ctx) {
        // Returns same type as the second argument (the timestamp)
        if (args != null && args.size() >= 2) {
            return ctx.resolveSecondArg(args, new DataType.DateTimeType());
        }
        return new DataType.DateTimeType();
    }

    @Override
    public Field<?> transform(List<Expression> args, TransformContext ctx) {
        return DSL.function(NAME, Object.class, ctx.transformAll(args));
    }
}
