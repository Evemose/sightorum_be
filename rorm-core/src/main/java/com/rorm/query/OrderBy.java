package com.rorm.query;

import org.jspecify.annotations.Nullable;

public record OrderBy(
    Expression expression,
    boolean ascending,
    @Nullable NullsHandling nullsHandling
) {

    /**
     * Backward-compatible constructor without nulls handling.
     */
    public OrderBy(Expression expression, boolean ascending) {
        this(expression, ascending, null);
    }

    public static OrderBy asc(Expression expression) {
        return new OrderBy(expression, true);
    }

    public static OrderBy desc(Expression expression) {
        return new OrderBy(expression, false);
    }

    public static OrderBy asc(Expression expression, NullsHandling nullsHandling) {
        return new OrderBy(expression, true, nullsHandling);
    }

    public static OrderBy desc(Expression expression, NullsHandling nullsHandling) {
        return new OrderBy(expression, false, nullsHandling);
    }

    /**
     * Returns a copy with the specified nulls handling.
     */
    public OrderBy withNullsHandling(NullsHandling nullsHandling) {
        return new OrderBy(expression, ascending, nullsHandling);
    }

    /**
     * Controls the placement of NULL values in ORDER BY.
     */
    public enum NullsHandling {
        NULLS_FIRST,
        NULLS_LAST
    }
}
