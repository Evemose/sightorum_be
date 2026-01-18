package com.rorm.engine.handler.function;

import com.rorm.metamodel.DataType;

public final class LengthFunction extends AbstractNumericFunction {
    public static final String NAME = "LENGTH";

    public LengthFunction() {
        super(false, new DataType.NumericType(10, 0));
    }

    @Override
    public String name() {
        return NAME;
    }
}
