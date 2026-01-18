package com.rorm.engine.handler.function;

import com.rorm.engine.handler.TransformContext;
import com.rorm.query.Expression;
import org.jooq.Field;
import org.jooq.impl.DSL;

import java.util.List;

public final class ConcatFunction extends AbstractStringFunction {
    public static final String NAME = "CONCAT";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Field<?> transform(List<Expression> args, TransformContext ctx) {
        return DSL.concat(ctx.transformAll(args));
    }
}
