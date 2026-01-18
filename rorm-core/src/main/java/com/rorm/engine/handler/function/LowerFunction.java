package com.rorm.engine.handler.function;

public final class LowerFunction extends AbstractStringFunction {
    public static final String NAME = "LOWER";

    @Override
    public String name() {
        return NAME;
    }
}
