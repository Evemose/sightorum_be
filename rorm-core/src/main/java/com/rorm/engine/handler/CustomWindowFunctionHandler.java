package com.rorm.engine.handler;

/**
 * Extension point for custom window function handlers.
 * <p>
 * Implement this interface to add support for database-specific or
 * application-specific window functions not covered by the built-in handlers.
 */
public non-sealed interface CustomWindowFunctionHandler extends WindowFunctionHandler {
}
