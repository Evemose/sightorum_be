package com.rorm.engine.handler.function;

/**
 * SPLIT_PART(string, delimiter, field) — splits a string on a delimiter and returns the nth field.
 * <p>
 * Example: {@code SPLIT_PART('a.b.c', '.', 2)} → {@code 'b'}
 */
public final class SplitPartFunction extends AbstractStringFunction {
    public static final String NAME = "SPLIT_PART";

    @Override
    public String name() {
        return NAME;
    }
}
