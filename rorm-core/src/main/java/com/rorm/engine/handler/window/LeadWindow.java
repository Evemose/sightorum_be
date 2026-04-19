package com.rorm.engine.handler.window;

import com.rorm.engine.handler.TransformContext;
import com.rorm.engine.handler.TypeResolutionContext;
import com.rorm.metamodel.DataType;
import com.rorm.query.Expression;
import com.rorm.query.WindowSpec;
import org.jooq.Field;
import org.jooq.impl.DSL;

import java.util.List;

public final class LeadWindow extends AbstractWindowFunction {
    public static final String NAME = "LEAD";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public DataType resolveType(List<Expression> args, TypeResolutionContext ctx) {
        return ctx.resolveFirstArg(args, null);
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Field<?> transform(List<Expression> args, WindowSpec windowSpec, TransformContext ctx) {
        var partition = transformPartitionFields(windowSpec, ctx);
        var order = transformOrderFields(windowSpec, ctx);
        var argFields = ctx.transformAll(args);

        if (argFields.length == 1) {
            return applyWindowSpec(DSL.lead(argFields[0]), partition, order, windowSpec.frame());
        } else if (argFields.length == 2) {
            int offset = ctx.extractInt(args.get(1));
            return applyWindowSpec(DSL.lead(argFields[0], offset), partition, order, windowSpec.frame());
        } else if (argFields.length == 3) {
            int offset = ctx.extractInt(args.get(1));
            return applyWindowSpec(DSL.lead(argFields[0], offset, (Field) argFields[2]), partition, order, windowSpec.frame());
        }
        throw new IllegalArgumentException("LEAD requires 1-3 arguments");
    }
}
