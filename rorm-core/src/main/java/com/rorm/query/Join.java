package com.rorm.query;

import com.rorm.metamodel.AliasedRoot;
import org.jspecify.annotations.Nullable;

/**
 * Represents an explicit JOIN clause in a query.
 *
 * @param aliasedRoot the root being joined, with its alias
 * @param joinType    the type of join (INNER, LEFT, RIGHT, CROSS)
 * @param onCondition the ON condition for the join (required for non-CROSS joins)
 */
public record Join(
    AliasedRoot aliasedRoot,
    JoinType joinType,
    @Nullable Expression onCondition
) {

    public Join {
        if (aliasedRoot == null) {
            throw new IllegalArgumentException("JoinedRoot cannot be null");
        }
        if (joinType.requiresOnCondition() && onCondition == null) {
            throw new IllegalArgumentException("ON condition is required for " + joinType + " joins");
        }
    }

    public enum JoinType {
        INNER,
        LEFT,
        RIGHT,
        CROSS,
        /**
         * CROSS JOIN LATERAL — no ON condition needed
         */
        CROSS_LATERAL,
        /**
         * LEFT JOIN LATERAL — ON condition required
         */
        LEFT_LATERAL;

        boolean requiresOnCondition() {
            return this != CROSS && this != CROSS_LATERAL;
        }
    }

}
