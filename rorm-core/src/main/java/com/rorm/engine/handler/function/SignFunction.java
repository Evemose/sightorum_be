package com.rorm.engine.handler.function;

import com.rorm.metamodel.DataType;

public final class SignFunction extends AbstractNumericFunction {
    public static final String NAME = "SIGN";

    public SignFunction() {
        super(false, new DataType.NumericType(1, 0));
    }

    @Override
    public String name() {
        return NAME;
    }
}
