package com.rorm.engine.handler;

import com.rorm.metamodel.DataType;
import com.rorm.query.Expression;
import com.rorm.query.WindowSpec;
import org.jooq.Field;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Handler for window function expressions.
 * <p>
 * Each implementation handles a specific window function (e.g., ROW_NUMBER, LAG, LEAD).
 * Handlers are registered in the {@link HandlerRegistry} and looked up by name.
 * <p>
 * The interface is sealed with built-in implementations, but allows custom
 * extensions via {@link CustomWindowFunctionHandler}.
 */
public sealed interface WindowFunctionHandler
    permits BuiltInWindowFunctionHandler, CustomWindowFunctionHandler {

    /**
     * The window function name used for matching in expression trees.
     * Should be lowercase for consistency with jOOQ conventions.
     *
     * @return the window function name
     */
    String name();

    /**
     * Resolves the return type of this window function given the arguments.
     *
     * @param args the function arguments
     * @param ctx  the type resolution context
     * @return the resolved return type
     */
    @Nullable
    DataType resolveType(List<Expression> args, TypeResolutionContext ctx);

    /**
     * Transforms the window function to a jOOQ Field.
     *
     * @param args       the function arguments
     * @param windowSpec the window specification (partition by, order by)
     * @param ctx        the transform context
     * @return the jOOQ Field representing this window function
     */
    Field<?> transform(List<Expression> args, WindowSpec windowSpec, TransformContext ctx);
}
