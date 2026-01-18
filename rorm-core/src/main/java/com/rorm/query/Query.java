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
    @Nullable List<OrderBy> orderBy,
    @Nullable Long limit,
    @Nullable Long offset
) {

    public Query {
        if (joins == null) {
            joins = new LinkedHashSet<>();
        }
        if (orderBy == null) {
            orderBy = List.of();
        }
    }

    @SuppressWarnings("unused")
    public static class QueryBuilder {
        @SuppressWarnings("FieldMayBeFinal")
        private SequencedSet<Join> joins = new LinkedHashSet<>();
        private final List<Expression> groupByExpressions = new ArrayList<>();
        private final List<OrderBy> orderByList = new ArrayList<>();

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

        public Query build() {
            GroupBy finalGroupBy = null;
            if (!groupByExpressions.isEmpty()) {
                finalGroupBy = new GroupBy(List.copyOf(groupByExpressions));
            }
            List<OrderBy> finalOrderBy = orderByList.isEmpty() ? null : List.copyOf(orderByList);
            return new Query(from, selector, joins, where, finalGroupBy, having, finalOrderBy, limit, offset);
        }
    }
}
