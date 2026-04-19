package com.rorm.engine.handler.function;

import com.rorm.engine.handler.TransformContext;
import com.rorm.query.Expression;
import org.jooq.Field;
import org.jooq.impl.DSL;

import java.util.List;

public final class RoundFunction extends AbstractNumericFunction {
    public static final String NAME = "ROUND";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Field<?> transform(List<Expression> args, TransformContext ctx) {
        if (args.size() == 1) {
            return DSL.field(
                "round(cast({0} as numeric))",
                Double.class,
                ctx.transform(args.getFirst())
            );
        }
        if (args.size() == 2) {
            return DSL.field(
                "round(cast({0} as numeric), cast({1} as integer))",
                Double.class,
                ctx.transform(args.get(0)),
                ctx.transform(args.get(1))
            );
        }
        throw new IllegalArgumentException("ROUND requires 1 or 2 arguments");
    }
}
