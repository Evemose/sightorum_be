package com.rorm.engine.handler;

/**
 * Extension point for custom binary operator handlers.
 * <p>
 * Implement this interface to add support for database-specific or
 * application-specific binary operators not covered by the built-in handlers.
 */
public non-sealed interface CustomBinaryOperatorHandler extends BinaryOperatorHandler {
}
