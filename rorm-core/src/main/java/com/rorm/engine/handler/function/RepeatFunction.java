package com.rorm.engine.handler.function;

import com.rorm.engine.handler.TransformContext;
import com.rorm.query.Expression;
import org.jooq.Field;
import org.jooq.impl.DSL;

import java.util.List;

public final class RepeatFunction extends AbstractStringFunction {
    public static final String NAME = "REPEAT";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Field<?> transform(List<Expression> args, TransformContext ctx) {
        if (args.size() != 2) {
            throw new IllegalArgumentException("REPEAT requires 2 arguments: text and count");
        }
        return DSL.field(
            "repeat({0}, cast({1} as integer))",
            String.class,
            ctx.transform(args.get(0)),
            ctx.transform(args.get(1))
        );
    }
}
