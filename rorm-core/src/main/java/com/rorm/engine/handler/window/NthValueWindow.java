package com.rorm.engine.handler.window;

import com.rorm.engine.handler.TransformContext;
import com.rorm.engine.handler.TypeResolutionContext;
import com.rorm.metamodel.DataType;
import com.rorm.query.Expression;
import com.rorm.query.WindowSpec;
import org.jooq.Field;
import org.jooq.impl.DSL;

import java.util.List;

public final class NthValueWindow extends AbstractWindowFunction {
    public static final String NAME = "NTH_VALUE";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public DataType resolveType(List<Expression> args, TypeResolutionContext ctx) {
        return ctx.resolveFirstArg(args, null);
    }

    @Override
    public Field<?> transform(List<Expression> args, WindowSpec windowSpec, TransformContext ctx) {
        if (args.size() < 2) {
            throw new IllegalArgumentException("NTH_VALUE requires 2 arguments");
        }
        var partition = transformPartitionFields(windowSpec, ctx);
        var order = transformOrderFields(windowSpec, ctx);
        int n = ctx.extractInt(args.get(1));
        return applyWindowSpec(DSL.nthValue(ctx.transform(args.getFirst()), n), partition, order, windowSpec.frame());
    }
}
