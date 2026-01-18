package com.rorm.engine.handler.function;

public final class UpperFunction extends AbstractStringFunction {
    public static final String NAME = "UPPER";

    @Override
    public String name() {
        return NAME;
    }
}
