package com.rorm.engine.handler.function;

public final class LTrimFunction extends AbstractStringFunction {
    public static final String NAME = "LTRIM";

    @Override
    public String name() {
        return NAME;
    }
}
