package com.rorm.engine.handler;

import com.rorm.metamodel.DataType;
import com.rorm.query.Expression;
import org.jooq.Field;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Handler for scalar function expressions.
 * <p>
 * Each implementation handles a specific function (e.g., UPPER, LOWER, COALESCE).
 * Handlers are registered in the {@link HandlerRegistry} and looked up by name.
 * <p>
 * The interface is sealed with built-in implementations, but allows custom
 * extensions via {@link CustomFunctionHandler}.
 */
public sealed interface FunctionHandler
    permits BuiltInFunctionHandler, CustomFunctionHandler {

    /**
     * The function name used for matching in expression trees.
     * Should be uppercase for consistency.
     *
     * @return the function name
     */
    String name();

    /**
     * Resolves the return type of this function given the arguments.
     *
     * @param args the function arguments
     * @param ctx  the type resolution context
     * @return the resolved return type, or null if the function returns NULL
     */
    @Nullable
    DataType resolveType(List<Expression> args, TypeResolutionContext ctx);

    /**
     * Transforms the function call to a jOOQ Field.
     *
     * @param args the function arguments
     * @param ctx  the transform context
     * @return the jOOQ Field representing this function call
     */
    Field<?> transform(List<Expression> args, TransformContext ctx);
}
