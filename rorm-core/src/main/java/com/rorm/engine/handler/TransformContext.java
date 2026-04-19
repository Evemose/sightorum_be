package com.rorm.engine.handler;

import com.rorm.query.Expression;
import org.jooq.Field;
import org.jooq.Select;
import org.jspecify.annotations.Nullable;

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
     * Transforms a subquery expression into a jOOQ Select for use in
     * contexts requiring a full query (e.g., IN subquery, EXISTS).
     *
     * @param subquery the subquery expression
     * @return the jOOQ Select, or null if the expression is not a subquery
     */
    @Nullable Select<?> transformAsSelect(Expression expression);

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
