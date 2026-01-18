package com.rorm.engine.handler.function;

public final class CeilFunction extends AbstractNumericFunction {
    public static final String NAME = "CEIL";

    @Override
    public String name() {
        return NAME;
    }
}
