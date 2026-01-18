package com.rorm.engine.handler.window;

import com.rorm.engine.handler.TransformContext;
import com.rorm.engine.handler.TypeResolutionContext;
import com.rorm.metamodel.DataType;
import com.rorm.query.Expression;
import com.rorm.query.WindowSpec;
import org.jooq.Field;
import org.jooq.impl.DSL;

import java.util.List;

public final class AvgWindow extends AbstractWindowFunction {
    public static final String NAME = "AVG";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public DataType resolveType(List<Expression> args, TypeResolutionContext ctx) {
        var argType = ctx.resolveFirstArg(args, null);
        if (argType instanceof DataType.NumericType(int precision, int scale)) {
            return new DataType.NumericType(precision, Math.max(scale, 6));
        }
        return new DataType.NumericType(19, 6);
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Field<?> transform(List<Expression> args, WindowSpec windowSpec, TransformContext ctx) {
        var partition = transformPartitionFields(windowSpec, ctx);
        var order = transformOrderFields(windowSpec, ctx);
        Field field = ctx.transform(args.getFirst());
        return applyWindowSpec(DSL.avg(field), partition, order);
    }
}
