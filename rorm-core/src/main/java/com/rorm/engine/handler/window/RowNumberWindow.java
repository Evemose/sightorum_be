package com.rorm.engine.handler.window;

import com.rorm.engine.handler.TransformContext;
import com.rorm.engine.handler.TypeResolutionContext;
import com.rorm.metamodel.DataType;
import com.rorm.query.Expression;
import com.rorm.query.WindowSpec;
import org.jooq.Field;
import org.jooq.impl.DSL;

import java.util.List;

public final class RowNumberWindow extends AbstractWindowFunction {
    public static final String NAME = "ROW_NUMBER";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public DataType resolveType(List<Expression> args, TypeResolutionContext ctx) {
        return new DataType.NumericType(19, 0);
    }

    @Override
    public Field<?> transform(List<Expression> args, WindowSpec windowSpec, TransformContext ctx) {
        var partition = transformPartitionFields(windowSpec, ctx);
        var order = transformOrderFields(windowSpec, ctx);
        return applyWindowSpec(DSL.rowNumber(), partition, order);
    }
}
