package com.rorm.engine.handler;

import com.rorm.engine.handler.operator.unary.*;

/**
 * Marker interface for built-in unary operator handlers.
 * <p>
 * This interface is sealed to enumerate all standard unary operators provided by the library.
 * For custom operators, implement {@link CustomUnaryOperatorHandler} instead.
 */
public sealed interface BuiltInUnaryOperatorHandler extends UnaryOperatorHandler permits
    IsNullOperator,
    IsNotNullOperator,
    IsTrueOperator,
    IsFalseOperator,
    NegateOperator,
    NotOperator {
}
