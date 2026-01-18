package com.rorm.engine.handler.function;

import com.rorm.metamodel.DataType;

public final class LnFunction extends AbstractNumericFunction {
    public static final String NAME = "LN";

    public LnFunction() {
        super(false, new DataType.NumericType(19, 10));
    }

    @Override
    public String name() {
        return NAME;
    }
}
