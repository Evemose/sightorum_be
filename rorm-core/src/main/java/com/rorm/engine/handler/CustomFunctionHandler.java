package com.rorm.engine.handler;

/**
 * Extension point for custom function handlers.
 * <p>
 * Implement this interface to add support for database-specific or
 * application-specific functions not covered by the built-in handlers.
 * <p>
 * Custom handlers are registered in the {@link HandlerRegistry} via Spring DI
 * or programmatically.
 * <p>
 * Example:
 * <pre>{@code
 * public class MyCustomFunction implements CustomFunctionHandler {
 *     public static final String NAME = "MY_CUSTOM_FUNC";
 *
 *     @Override
 *     public String name() {
 *         return NAME;
 *     }
 *
 *     @Override
 *     public DataType resolveType(List<Expression> args, TypeResolutionContext ctx) {
 *         return new DataType.StringType();
 *     }
 *
 *     @Override
 *     public Field<?> transform(List<Expression> args, TransformContext ctx) {
 *         return DSL.function(NAME, String.class, ctx.transformAll(args));
 *     }
 * }
 * }</pre>
 */
public non-sealed interface CustomFunctionHandler extends FunctionHandler {
}
