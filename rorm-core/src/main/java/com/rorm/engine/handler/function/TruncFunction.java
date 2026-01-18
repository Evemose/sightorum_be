package com.rorm.engine.handler.function;

public final class TruncFunction extends AbstractNumericFunction {
    public static final String NAME = "TRUNC";

    @Override
    public String name() {
        return NAME;
    }
}
