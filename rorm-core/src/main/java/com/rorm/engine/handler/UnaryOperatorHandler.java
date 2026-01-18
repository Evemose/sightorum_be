package com.rorm.engine.handler;

import com.rorm.metamodel.DataType;
import com.rorm.query.Expression;
import org.jooq.Field;

/**
 * Handler for unary operator expressions.
 * <p>
 * Each implementation handles a specific unary operator (e.g., IS_NULL, NOT, NEGATE).
 * Handlers are registered in the {@link HandlerRegistry} and looked up by name.
 * <p>
 * The interface is sealed with built-in implementations, but allows custom
 * extensions via {@link CustomUnaryOperatorHandler}.
 */
public sealed interface UnaryOperatorHandler
    permits BuiltInUnaryOperatorHandler, CustomUnaryOperatorHandler {

    /**
     * The operator name used for matching in expression trees.
     * Should be uppercase for consistency.
     *
     * @return the operator name
     */
    String name();

    /**
     * Resolves the return type of this operator given the operand.
     *
     * @param operand the operand expression
     * @param ctx     the type resolution context
     * @return the resolved return type
     */
    DataType resolveType(Expression operand, TypeResolutionContext ctx);

    /**
     * Transforms the unary operator expression to a jOOQ Field.
     *
     * @param operand the operand expression
     * @param ctx     the transform context
     * @return the jOOQ Field representing this operator application
     */
    Field<?> transform(Expression operand, TransformContext ctx);
}
