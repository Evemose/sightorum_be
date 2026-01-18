package com.rorm.engine.handler.function;

public final class RTrimFunction extends AbstractStringFunction {
    public static final String NAME = "RTRIM";

    @Override
    public String name() {
        return NAME;
    }
}
