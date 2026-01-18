package com.rorm.engine.handler;

import com.rorm.engine.handler.operator.binary.*;

/**
 * Marker interface for built-in binary operator handlers.
 * <p>
 * This interface is sealed to enumerate all standard binary operators provided by the library.
 * For custom operators, implement {@link CustomBinaryOperatorHandler} instead.
 */
public sealed interface BuiltInBinaryOperatorHandler extends BinaryOperatorHandler permits
    // Comparison operators
    EqualsOperator,
    GreaterThanOperator,
    GreaterThanOrEqualOperator,
    LessThanOperator,
    LessThanOrEqualOperator,
    LikeOperator,
    InOperator,
    // Arithmetic operators
    AddOperator,
    SubtractOperator,
    MultiplyOperator,
    DivideOperator,
    ModuloOperator,
    // Logical operators
    AndOperator,
    OrOperator {
}
