package com.rorm.query;

import com.rorm.metamodel.AliasedRoot;
import lombok.Builder;
import lombok.With;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.SequencedSet;

@With
@Builder
public record Query(
    AliasedRoot from,
    Selector selector,
    SequencedSet<Join> joins,
    @Nullable Expression where,
    @Nullable GroupBy groupBy,
    @Nullable Expression having,
    List<OrderBy> orderBy,
    @Nullable Long limit,
    @Nullable Long offset,
    @Nullable Boolean withTies,
    List<CteDefinition> ctes,
    List<SetOperation> setOperations
) {

    /**
     * Backward-compatible constructor without withTies, CTEs and set operations.
     */
    public Query(AliasedRoot from, Selector selector, SequencedSet<Join> joins,
                 @Nullable Expression where, @Nullable GroupBy groupBy, @Nullable Expression having,
                 @Nullable List<OrderBy> orderBy, @Nullable Long limit, @Nullable Long offset) {
        this(from, selector, joins, where, groupBy, having, orderBy, limit, offset, null, null, null);
    }

    public Query {
        if (joins == null) {
            joins = new LinkedHashSet<>();
        }
        if (orderBy == null) {
            orderBy = List.of();
        }
        if (ctes == null) {
            ctes = List.of();
        }
        if (setOperations == null) {
            setOperations = List.of();
        }
    }

    /**
     * Backward-compatible constructor without withTies and set operations.
     */
    public Query(AliasedRoot from, Selector selector, SequencedSet<Join> joins,
                 @Nullable Expression where, @Nullable GroupBy groupBy, @Nullable Expression having,
                 @Nullable List<OrderBy> orderBy, @Nullable Long limit, @Nullable Long offset,
                 @Nullable List<CteDefinition> ctes) {
        this(from, selector, joins, where, groupBy, having, orderBy, limit, offset, null, ctes, null);
    }

    /**
     * Backward-compatible constructor without withTies.
     */
    public Query(AliasedRoot from, Selector selector, SequencedSet<Join> joins,
                 @Nullable Expression where, @Nullable GroupBy groupBy, @Nullable Expression having,
                 @Nullable List<OrderBy> orderBy, @Nullable Long limit, @Nullable Long offset,
                 @Nullable List<CteDefinition> ctes, @Nullable List<SetOperation> setOperations) {
        this(from, selector, joins, where, groupBy, having, orderBy, limit, offset, null, ctes, setOperations);
    }

    @SuppressWarnings("unused")
    public static class QueryBuilder {
        @SuppressWarnings("FieldMayBeFinal")
        private SequencedSet<Join> joins = new LinkedHashSet<>();
        private final List<CteDefinition> cteList = new ArrayList<>();
        private final List<Expression> groupByExpressions = new ArrayList<>();
        private final List<OrderBy> orderByList = new ArrayList<>();
        private final List<SetOperation> setOperationList = new ArrayList<>();
        private Boolean withTies;

        public QueryBuilder groupBy(GroupBy groupBy) {
            if (groupBy != null) {
                this.groupByExpressions.addAll(groupBy.expressions());
            }
            return this;
        }

        public QueryBuilder orderBy(OrderBy orderBy) {
            if (orderBy != null) {
                this.orderByList.add(orderBy);
            }
            return this;
        }

        public QueryBuilder cte(CteDefinition cte) {
            if (cte != null) {
                this.cteList.add(cte);
            }
            return this;
        }

        public QueryBuilder setOperation(SetOperation setOp) {
            if (setOp != null) {
                this.setOperationList.add(setOp);
            }
            return this;
        }

        public QueryBuilder withTies(Boolean withTies) {
            this.withTies = withTies;
            return this;
        }

        public Query build() {
            GroupBy finalGroupBy = null;
            if (!groupByExpressions.isEmpty()) {
                finalGroupBy = new GroupBy(List.copyOf(groupByExpressions));
            }
            List<OrderBy> finalOrderBy = orderByList.isEmpty() ? null : List.copyOf(orderByList);
            List<CteDefinition> finalCtes = cteList.isEmpty() ? null : List.copyOf(cteList);
            List<SetOperation> finalSetOps = setOperationList.isEmpty() ? null : List.copyOf(setOperationList);
            return new Query(from, selector, joins, where, finalGroupBy, having, finalOrderBy, limit, offset, withTies, finalCtes, finalSetOps);
        }
    }
}
