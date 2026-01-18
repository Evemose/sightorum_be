package com.rorm.engine.handler;

/**
 * Extension point for custom ternary operator handlers.
 * <p>
 * Implement this interface to add support for database-specific or
 * application-specific ternary operators not covered by the built-in handlers.
 */
public non-sealed interface CustomTernaryOperatorHandler extends TernaryOperatorHandler {
}
