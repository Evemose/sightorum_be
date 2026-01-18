package com.rorm.engine;

/**
 * Exception thrown when type resolution fails for an expression.
 */
public class TypeResolutionException extends RuntimeException {

    public TypeResolutionException(String message) {
        super(message);
    }

    public TypeResolutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
