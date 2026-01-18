package com.rorm.engine.handler.function;

import com.rorm.metamodel.DataType;

public final class PositionFunction extends AbstractNumericFunction {
    public static final String NAME = "POSITION";

    public PositionFunction() {
        super(false, new DataType.NumericType(10, 0));
    }

    @Override
    public String name() {
        return NAME;
    }
}
