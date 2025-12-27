package com.rorm.query;

import org.jspecify.annotations.Nullable;

public record Join(
    JoinType joinType,
    @Nullable Expression onCondition
) {

    public enum JoinType {
        INNER,
        LEFT,
        RIGHT,
        CROSS
    }

}
