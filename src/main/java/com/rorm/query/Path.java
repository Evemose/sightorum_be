package com.rorm.query;

import com.rorm.metamodel.Attribute;
import org.jspecify.annotations.Nullable;

public record Path(
    Attribute target,
    @Nullable Path parent
) implements Expression {

}
