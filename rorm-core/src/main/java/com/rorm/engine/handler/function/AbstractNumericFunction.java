package com.rorm.engine.handler.function;

import com.rorm.engine.handler.BuiltInFunctionHandler;
import com.rorm.engine.handler.TransformContext;
import com.rorm.engine.handler.TypeResolutionContext;
import com.rorm.metamodel.DataType;
import com.rorm.metamodel.DataType.NumericType;
import com.rorm.query.Expression;
import org.jooq.Field;
import org.jooq.impl.DSL;

import java.util.List;

/**
 * Base class for functions that preserve or return numeric types.
 */
public abstract non-sealed class AbstractNumericFunction implements BuiltInFunctionHandler {

    private final boolean preserveArgType;
    private final DataType defaultType;

    protected AbstractNumericFunction() {
        this(true, new DataType.NumericType(15, 6));
    }

    protected AbstractNumericFunction(boolean preserveArgType, DataType defaultType) {
        this.preserveArgType = preserveArgType;
        this.defaultType = defaultType;
    }

    @Override
    public DataType resolveType(List<Expression> args, TypeResolutionContext ctx) {
        if (preserveArgType && args != null && !args.isEmpty()) {
            return ctx.resolveFirstArg(args, defaultType);
        }
        return defaultType;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Field<?> transform(List<Expression> args, TransformContext ctx) {
        var isFloatingPoint = defaultType instanceof NumericType nt && nt.scale() > 0;
        return DSL.function(
            name(),
            isFloatingPoint ? (Class<Number>) (Class<?>) Double.class : (Class<Number>) (Class<?>) Long.class,
            ctx.transformAll(args)
        );
    }
}
