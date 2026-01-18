package com.rorm.engine.handler;

import com.rorm.query.Expression;
import org.jooq.Field;

import java.util.List;

/**
 * Context provided to handlers during jOOQ transformation.
 */
public interface TransformContext {

    /**
     * Transforms a list of expressions to an array of jOOQ Fields.
     *
     * @param expressions the expressions to transform
     * @return array of jOOQ Fields
     */
    default Field<?>[] transformAll(List<Expression> expressions) {
        return expressions.stream()
            .map(this::transform)
            .toArray(Field[]::new);
    }

    /**
     * Transforms a nested expression to a jOOQ Field.
     *
     * @param expression the expression to transform
     * @return the jOOQ Field
     */
    Field<?> transform(Expression expression);

    /**
     * Extracts an integer value from a Literal expression.
     *
     * @param expression the expression (must be a Literal with numeric value)
     * @return the integer value
     * @throws IllegalArgumentException if expression is not a numeric literal
     */
    default int extractInt(Expression expression) {
        if (expression instanceof Expression.Literal(Number value)) {
            return value.intValue();
        }
        throw new IllegalArgumentException("Expected integer literal, got: " + expression);
    }
}
