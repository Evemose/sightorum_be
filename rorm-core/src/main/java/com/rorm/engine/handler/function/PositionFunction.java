package com.rorm.engine.handler.function;

import com.rorm.engine.handler.TransformContext;
import com.rorm.metamodel.DataType;
import com.rorm.query.Expression;
import org.jooq.Field;
import org.jooq.impl.DSL;

import java.util.List;

public final class PositionFunction extends AbstractNumericFunction {
    public static final String NAME = "POSITION";

    public PositionFunction() {
        super(false, new DataType.NumericType(10, 0));
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Field<?> transform(List<Expression> args, TransformContext ctx) {
        if (args.size() != 2) {
            throw new IllegalArgumentException("POSITION requires 2 arguments: needle and haystack");
        }
        return DSL.field(
            "position({0} in {1})",
            Long.class,
            ctx.transform(args.get(0)),
            ctx.transform(args.get(1))
        );
    }
}
