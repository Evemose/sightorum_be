package com.rorm.query;

import com.rorm.engine.handler.operator.binary.*;
import com.rorm.engine.handler.operator.ternary.BetweenOperator;
import com.rorm.engine.handler.operator.unary.*;

/**
 * Standard operators supported by the query system.
 * <p>
 * Operators are categorized by arity (unary, binary, ternary).
 * Each constant provides a type-safe identifier that maps to the corresponding
 * operator handler.
 */
public sealed interface StandardOperator {

    /**
     * Returns the operator name identifier used for handler lookup.
     *
     * @return the operator name
     */
    String identifier();

    /**
     * Standard unary operators (single operand).
     */
    enum Unary implements StandardOperator {
        IS_NULL(IsNullOperator.NAME),
        IS_NOT_NULL(IsNotNullOperator.NAME),
        IS_TRUE(IsTrueOperator.NAME),
        IS_FALSE(IsFalseOperator.NAME),
        NEGATE(NegateOperator.NAME),
        NOT(NotOperator.NAME),
        EXISTS(ExistsOperator.NAME);

        private final String identifier;

        Unary(String identifier) {
            this.identifier = identifier;
        }

        @Override
        public String identifier() {
            return identifier;
        }
    }

    /**
     * Standard binary operators (two operands).
     * <p>
     * Note: For negated operators (NOT EQUALS, NOT LIKE, NOT IN), use the NOT unary operator
     * combined with the positive operator instead. For example:
     * <ul>
     *   <li>Instead of NOT_EQUALS: use NOT(EQUALS(...))</li>
     *   <li>Instead of NOT_LIKE: use NOT(LIKE(...))</li>
     *   <li>Instead of NOT_IN: use NOT(IN(...))</li>
     * </ul>
     */
    enum Binary implements StandardOperator {
        // Comparison
        EQUALS(EqualsOperator.NAME),
        GREATER_THAN(GreaterThanOperator.NAME),
        GREATER_THAN_OR_EQUAL(GreaterThanOrEqualOperator.NAME),
        LESS_THAN(LessThanOperator.NAME),
        LESS_THAN_OR_EQUAL(LessThanOrEqualOperator.NAME),
        LIKE(LikeOperator.NAME),
        IN(InOperator.NAME),

        // Arithmetic
        ADD(AddOperator.NAME),
        SUBTRACT(SubtractOperator.NAME),
        MULTIPLY(MultiplyOperator.NAME),
        DIVIDE(DivideOperator.NAME),
        MODULO(ModuloOperator.NAME),

        // Logical
        AND(AndOperator.NAME),
        OR(OrOperator.NAME);

        private final String identifier;

        Binary(String identifier) {
            this.identifier = identifier;
        }

        @Override
        public String identifier() {
            return identifier;
        }
    }

    /**
     * Standard ternary operators (three operands).
     * <p>
     * Note: For NOT BETWEEN, use the NOT unary operator combined with BETWEEN instead.
     * For example: NOT(BETWEEN(value, lower, upper))
     */
    enum Ternary implements StandardOperator {
        BETWEEN(BetweenOperator.NAME);

        private final String identifier;

        Ternary(String identifier) {
            this.identifier = identifier;
        }

        @Override
        public String identifier() {
            return identifier;
        }
    }
}
