package com.rorm.engine.handler.function;

import com.rorm.engine.handler.BuiltInFunctionHandler;
import com.rorm.engine.handler.TransformContext;
import com.rorm.engine.handler.TypeResolutionContext;
import com.rorm.metamodel.DataType;
import com.rorm.query.Expression;
import org.jooq.Field;
import org.jooq.impl.DSL;

import java.util.List;

public final class CoalesceFunction implements BuiltInFunctionHandler {
    public static final String NAME = "COALESCE";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public DataType resolveType(List<Expression> args, TypeResolutionContext ctx) {
        // Returns type of first non-null argument
        return ctx.resolveFirstArg(args, null);
    }

    @Override
    public Field<?> transform(List<Expression> args, TransformContext ctx) {
        return DSL.coalesce(ctx.transformAll(args));
    }
}
