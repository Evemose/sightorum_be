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
        var fields = ctx.transformAll(args);
        // Split first arg from rest to avoid Java varargs ambiguity:
        // DSL.coalesce(Field<?>[]) matches coalesce(T, T...) treating the array as a single value,
        // producing invalid SQL like cast('{...}' as any[][])
        return DSL.coalesce(fields[0], Arrays.copyOfRange(fields, 1, fields.length));
    }
}
