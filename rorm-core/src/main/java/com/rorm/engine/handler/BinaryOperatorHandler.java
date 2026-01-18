package com.rorm.engine.handler;

import com.rorm.metamodel.DataType;
import com.rorm.query.Expression;
import org.jooq.Field;

/**
 * Handler for binary operator expressions.
 * <p>
 * Each implementation handles a specific binary operator (e.g., EQUALS, ADD, AND).
 * Handlers are registered in the {@link HandlerRegistry} and looked up by name.
 * <p>
 * The interface is sealed with built-in implementations, but allows custom
 * extensions via {@link CustomBinaryOperatorHandler}.
 */
public sealed interface BinaryOperatorHandler
    permits BuiltInBinaryOperatorHandler, CustomBinaryOperatorHandler {

    /**
     * The operator name used for matching in expression trees.
     * Should be uppercase for consistency.
     *
     * @return the operator name
     */
    String name();

    /**
     * Resolves the return type of this operator given the operands.
     *
     * @param left  the left operand expression
     * @param right the right operand expression
     * @param ctx   the type resolution context
     * @return the resolved return type
     */
    DataType resolveType(Expression left, Expression right, TypeResolutionContext ctx);

    /**
     * Transforms the binary operator expression to a jOOQ Field.
     *
     * @param left  the left operand expression
     * @param right the right operand expression
     * @param ctx   the transform context
     * @return the jOOQ Field representing this operator application
     */
    Field<?> transform(Expression left, Expression right, TransformContext ctx);
}
