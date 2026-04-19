package com.rorm.query;

import org.jspecify.annotations.Nullable;

import java.util.List;

public record WindowSpec(
    @Nullable List<Expression> partitionBy,
    @Nullable List<OrderBy> orderBy,
    @Nullable WindowFrame frame
) {

    /**
     * Backward-compatible constructor without frame.
     */
    public WindowSpec(@Nullable List<Expression> partitionBy, @Nullable List<OrderBy> orderBy) {
        this(partitionBy, orderBy, null);
    }
}
