package com.rorm.engine.handler.function;

public final class RoundFunction extends AbstractNumericFunction {
    public static final String NAME = "ROUND";

    @Override
    public String name() {
        return NAME;
    }
}
