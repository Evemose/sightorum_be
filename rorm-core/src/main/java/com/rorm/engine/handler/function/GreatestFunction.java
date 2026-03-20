package com.rorm.engine.handler.function;

import com.rorm.engine.handler.BuiltInFunctionHandler;
import com.rorm.engine.handler.TransformContext;
import com.rorm.engine.handler.TypeResolutionContext;
import com.rorm.metamodel.DataType;
import com.rorm.query.Expression;
import org.jooq.Field;
import org.jooq.impl.DSL;

import java.util.Arrays;
import java.util.List;

public final class GreatestFunction implements BuiltInFunctionHandler {
    public static final String NAME = "GREATEST";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public DataType resolveType(List<Expression> args, TypeResolutionContext ctx) {
        return ctx.resolveFirstArg(args, null);
    }

    @Override
    public Field<?> transform(List<Expression> args, TransformContext ctx) {
        var fields = ctx.transformAll(args);
        return DSL.greatest(fields[0], Arrays.copyOfRange(fields, 1, fields.length));
    }
}
