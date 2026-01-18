package com.rorm.engine.handler;

/**
 * Extension point for custom unary operator handlers.
 * <p>
 * Implement this interface to add support for database-specific or
 * application-specific unary operators not covered by the built-in handlers.
 */
public non-sealed interface CustomUnaryOperatorHandler extends UnaryOperatorHandler {
}
