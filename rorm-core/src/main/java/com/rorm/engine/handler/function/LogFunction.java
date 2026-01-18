package com.rorm.engine.handler.function;

import com.rorm.metamodel.DataType;

public final class LogFunction extends AbstractNumericFunction {
    public static final String NAME = "LOG";

    public LogFunction() {
        super(false, new DataType.NumericType(19, 10));
    }

    @Override
    public String name() {
        return NAME;
    }
}
