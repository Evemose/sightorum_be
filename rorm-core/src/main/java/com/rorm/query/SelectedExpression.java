package com.rorm.query;

import org.jspecify.annotations.Nullable;

public record SelectedExpression(
    Expression expression,
    @Nullable String alias
) {
}
