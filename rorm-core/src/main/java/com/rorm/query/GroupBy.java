package com.rorm.query;

import java.util.List;

public record GroupBy(
    List<Expression> expressions
) {
    public GroupBy(Expression expression) {
        this(List.of(expression));
    }
}
