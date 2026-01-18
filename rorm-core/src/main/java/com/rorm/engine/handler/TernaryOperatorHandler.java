package com.rorm.engine.handler;

import com.rorm.metamodel.DataType;
import com.rorm.query.Expression;
import org.jooq.Field;

/**
 * Handler for ternary operator expressions.
 * <p>
 * Each implementation handles a specific ternary operator (e.g., BETWEEN, NOT_BETWEEN).
 * Handlers are registered in the {@link HandlerRegistry} and looked up by name.
 * <p>
 * The interface is sealed with built-in implementations, but allows custom
 * extensions via {@link CustomTernaryOperatorHandler}.
 */
public sealed interface TernaryOperatorHandler
    permits BuiltInTernaryOperatorHandler, CustomTernaryOperatorHandler {

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
     * @param first  the first operand (the value being tested)
     * @param second the second operand (lower bound for BETWEEN)
     * @param third  the third operand (upper bound for BETWEEN)
     * @param ctx    the type resolution context
     * @return the resolved return type
     */
    DataType resolveType(Expression first, Expression second, Expression third, TypeResolutionContext ctx);

    /**
     * Transforms the ternary operator expression to a jOOQ Field.
     *
     * @param first  the first operand
     * @param second the second operand
     * @param third  the third operand
     * @param ctx    the transform context
     * @return the jOOQ Field representing this operator application
     */
    Field<?> transform(Expression first, Expression second, Expression third, TransformContext ctx);
}
