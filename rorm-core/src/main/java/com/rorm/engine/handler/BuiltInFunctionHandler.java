package com.rorm.engine.handler;

import com.rorm.engine.handler.function.*;

/**
 * Marker interface for built-in function handlers.
 * <p>
 * This interface is sealed to enumerate all standard functions provided by the library.
 * For custom functions, implement {@link CustomFunctionHandler} instead.
 */
public sealed interface BuiltInFunctionHandler extends FunctionHandler permits
    // Abstract base classes (non-sealed, allowing extension)
    AbstractStringFunction,
    AbstractNumericFunction,
    // Direct implementations
    CastFunction,
    CoalesceFunction,
    NullIfFunction,
    GreatestFunction,
    LeastFunction,
    CaseFunction,
    NowFunction,
    CurrentDateFunction,
    CurrentTimeFunction,
    DateTruncFunction,
    ExtractFunction {
}
