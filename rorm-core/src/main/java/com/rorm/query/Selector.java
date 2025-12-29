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
    }

    record SingleExprSelector(Expression expression, boolean distinct, @Nullable String alias) implements Selector {
    }

    record MultiExprSelector(Set<SelectedExpression> expressions, boolean distinct) implements Selector {
    }

}
