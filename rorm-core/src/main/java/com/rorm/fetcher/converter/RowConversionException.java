package com.rorm.fetcher.converter;

/**
 * Exception thrown when row conversion fails.
 */
public class RowConversionException extends RuntimeException {

    public RowConversionException(String message) {
        super(message);
    }

    public RowConversionException(String message, Throwable cause) {
        super(message, cause);
    }
}
