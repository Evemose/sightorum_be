package com.rorm.engine.handler;

import com.rorm.engine.handler.operator.ternary.BetweenOperator;
import com.rorm.engine.handler.operator.ternary.NotBetweenOperator;

/**
 * Marker interface for built-in ternary operator handlers.
 * <p>
 * This interface is sealed to enumerate all standard ternary operators provided by the library.
 * For custom operators, implement {@link CustomTernaryOperatorHandler} instead.
 */
public sealed interface BuiltInTernaryOperatorHandler extends TernaryOperatorHandler permits
    BetweenOperator,
    NotBetweenOperator {
}
