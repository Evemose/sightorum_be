package com.rorm.engine.handler;

import com.rorm.engine.handler.window.AbstractWindowFunction;

/**
 * Marker interface for built-in window function handlers.
 * <p>
 * This interface is sealed to enumerate all standard window functions provided by the library.
 * For custom window functions, implement {@link CustomWindowFunctionHandler} instead.
 */
public sealed interface BuiltInWindowFunctionHandler extends WindowFunctionHandler permits
    // Abstract base class (non-sealed, allowing extension)
    AbstractWindowFunction {
}
