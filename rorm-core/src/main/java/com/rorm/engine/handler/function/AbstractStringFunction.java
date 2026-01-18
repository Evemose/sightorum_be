package com.rorm.engine.handler.function;

import com.rorm.engine.handler.BuiltInFunctionHandler;
import com.rorm.engine.handler.TransformContext;
import com.rorm.engine.handler.TypeResolutionContext;
import com.rorm.metamodel.DataType;
import com.rorm.query.Expression;
import org.jooq.Field;
import org.jooq.impl.DSL;

import java.util.List;

/**
 * Base class for functions that always return StringType.
 */
public abstract non-sealed class AbstractStringFunction implements BuiltInFunctionHandler {

    @Override
    public DataType resolveType(List<Expression> args, TypeResolutionContext ctx) {
        return new DataType.StringType();
    }

    @Override
    public Field<?> transform(List<Expression> args, TransformContext ctx) {
        return DSL.function(name(), String.class, ctx.transformAll(args));
    }
}
