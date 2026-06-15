package com.rorm.query;

import com.rorm.metamodel.Root;
import com.rorm.query.Selector.MultiExprSelector;
import com.rorm.query.Selector.RootSelector;
import com.rorm.query.Selector.SingleExprSelector;
import org.jspecify.annotations.Nullable;

import java.util.Set;

public sealed interface Selector permits RootSelector, SingleExprSelector, MultiExprSelector {

    boolean distinct();

    record RootSelector(Root root, boolean distinct) implements Selector {

        public static RootSelector of(Root root) {
            return new RootSelector(root, false);
        }

        public static RootSelector ofDistinct(Root root) {
            return new RootSelector(root, true);
        }

    }

    record SingleExprSelector(Expression expression, boolean distinct, @Nullable String alias) implements Selector {
    }

    /**
     * Multiple expression selector.
     *
     * <p>The {@code expressions} set is order-significant in two places:
     * (1) the column order of the result rows, and
     * (2) when this query is the body of a CTE that declares explicit column
     *     names, the positional mapping between WITH-clause column names and
     *     the inner SELECT's projection. A {@link java.util.LinkedHashSet} (or
     *     any sequenced set) preserves the insertion order callers care about.
     */
    record MultiExprSelector(Set<SelectedExpression> expressions, boolean distinct) implements Selector {
    }

}
