package com.rorm.query;

public record OrderBy(
    Expression expression,
    boolean ascending
) {

    public static OrderBy asc(Expression expression) {
        return new OrderBy(expression, true);
    }

    public static OrderBy desc(Expression expression) {
        return new OrderBy(expression, false);
    }

}
