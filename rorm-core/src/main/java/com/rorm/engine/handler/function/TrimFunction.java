package com.rorm.engine.handler.function;

public final class TrimFunction extends AbstractStringFunction {
    public static final String NAME = "TRIM";

    @Override
    public String name() {
        return NAME;
    }
}
