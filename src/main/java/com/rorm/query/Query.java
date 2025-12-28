package com.rorm.query;

import com.rorm.metamodel.Root;
import lombok.Builder;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.SequencedSet;

@Builder
public record Query(
    Root from,
    Selector selector,
    SequencedSet<Join> joins,
    @Nullable Expression where,
    @Nullable GroupBy groupBy,
    @Nullable Expression having,
    @Nullable OrderBy orderBy,
    @Nullable Long limit,
    @Nullable Long offset
) {

    public long effectiveLimit() {
        return limit != null ? limit : Long.MAX_VALUE;
    }

    public long effectiveOffset() {
        return offset != null ? offset : 0L;
    }


    @SuppressWarnings("unused")
    public static class QueryBuilder {
        @SuppressWarnings("FieldMayBeFinal")
        private SequencedSet<Join> joins = new LinkedHashSet<>();
    }
}
