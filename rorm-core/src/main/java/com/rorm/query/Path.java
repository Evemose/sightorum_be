package com.rorm.query;

import com.rorm.metamodel.PathTarget;
import org.jspecify.annotations.Nullable;

public record Path(
    PathTarget target,
    @Nullable Path parent
) implements Expression {

    public Path(PathTarget target) {
        this(target, null);
    }

}
