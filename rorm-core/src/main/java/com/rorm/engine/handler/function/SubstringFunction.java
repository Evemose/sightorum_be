package com.rorm.engine.handler.function;

public final class SubstringFunction extends AbstractStringFunction {
    public static final String NAME = "SUBSTRING";

    @Override
    public String name() {
        return NAME;
    }
}
