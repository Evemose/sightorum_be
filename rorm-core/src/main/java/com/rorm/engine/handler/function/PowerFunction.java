package com.rorm.engine.handler.function;

import com.rorm.metamodel.DataType;

public final class PowerFunction extends AbstractNumericFunction {
    public static final String NAME = "POWER";

    public PowerFunction() {
        super(false, new DataType.NumericType(19, 10));
    }

    @Override
    public String name() {
        return NAME;
    }
}
