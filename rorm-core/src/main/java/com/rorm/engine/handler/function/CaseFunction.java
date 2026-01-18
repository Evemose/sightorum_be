package com.rorm.engine.handler.function;

import com.rorm.engine.handler.BuiltInFunctionHandler;
import com.rorm.engine.handler.TransformContext;
import com.rorm.engine.handler.TypeResolutionContext;
import com.rorm.metamodel.DataType;
import com.rorm.query.Expression;
import org.jooq.Field;
import org.jooq.impl.DSL;

import java.util.List;

public final class CaseFunction implements BuiltInFunctionHandler {
    public static final String NAME = "CASE";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public DataType resolveType(List<Expression> args, TypeResolutionContext ctx) {
        // CASE returns type of the first THEN clause (second argument)
        if (args != null && args.size() >= 2) {
            return ctx.resolveSecondArg(args, null);
        }
        return null;
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Field<?> transform(List<Expression> args, TransformContext ctx) {
        // For simple CASE with condition, then, else
        var fields = ctx.transformAll(args);
        if (fields.length >= 3) {
            Field condField = fields[0];
            Field thenField = fields[1];
            Field elseField = fields[2];
            return DSL.when(DSL.condition((Field<Boolean>) condField), thenField)
                .otherwise(elseField);
        } else if (fields.length == 2) {
            Field condField = fields[0];
            Field thenField = fields[1];
            return DSL.when(DSL.condition((Field<Boolean>) condField), thenField);
        }
        throw new IllegalArgumentException("CASE requires at least 2 arguments");
    }
}
