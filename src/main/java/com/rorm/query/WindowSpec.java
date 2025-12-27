package com.rorm.query;

import org.jspecify.annotations.Nullable;

import java.util.List;

public record WindowSpec(
    @Nullable List<Expression> partitionBy,
    @Nullable List<OrderBy> orderBy
) {
}
