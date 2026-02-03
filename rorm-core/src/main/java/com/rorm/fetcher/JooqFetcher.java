package com.rorm.fetcher;

import com.rorm.engine.QueryTransformer;
import com.rorm.fetcher.converter.*;
import com.rorm.metamodel.Root;
import com.rorm.query.Query;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.jspecify.annotations.Nullable;

import java.lang.ScopedValue.CallableOp;
import java.util.*;

/**
 * JOOQ-based implementation of the Fetcher interface.
 */
@RequiredArgsConstructor
public class JooqFetcher implements Fetcher {

    private static final ScopedValue<String> CURRENT_SCHEMA = ScopedValue.newInstance();
    private final DSLContext dsl;
    private final QueryTransformer queryTransformer;

    @Override
    public <T> List<T> queryForType(Query query, TypeRef<T> typeRef) {
        return query(query, selectConverter(typeRef.getType()));
    }

    @Override
    public <T> List<T> query(Query query, RowConverter<T> converter) {
        var jooqQuery = queryTransformer.transform(query, CURRENT_SCHEMA.get());

        @SuppressWarnings("unchecked")
        var results = (Result<Record>) dsl.fetch(jooqQuery);

        var converted = new ArrayList<T>(results.size());

        for (var record : results) {
            var row = new JooqRow(record);
            var consumed = new HashSet<String>();

            converted.add(converter.convert(row, consumed));
            warnDanglingColumns(row, consumed);
        }

        return converted;
    }

    @SuppressWarnings("unchecked")
    private <T> RowConverter<T> selectConverter(Class<T> type) {
        if (Map.class.isAssignableFrom(type)) {
            return (RowConverter<T>) new MapConverter();
        }
        if (type.isRecord()) {
            return (RowConverter<T>) new RecordConverter<>((Class<? extends java.lang.Record>) type);
        }
        return new BeanConverter<>(type);
    }

    private void warnDanglingColumns(Row row, Set<String> consumed) {
        Arrays.stream(row.getColumnNames())
            .filter(col -> !consumed.contains(col))
            .forEach(col -> System.err.println("WARNING: Dangling column not consumed: " + col));
    }

    @Override
    public <T> List<T> queryForRoot(Query query, Root root, Class<T> resultType) {
        return query(query, selectRootConverter(resultType, root));
    }

    @SuppressWarnings("unchecked")
    private <T> RowConverter<T> selectRootConverter(Class<T> type, Root root) {
        if (Map.class.isAssignableFrom(type)) {
            return (RowConverter<T>) new RootMapConverter(root);
        }
        if (type.isRecord()) {
            return (RowConverter<T>) new RootRecordConverter<>((Class<? extends java.lang.Record>) type, root);
        }
        return new RootBeanConverter<>(type, root);
    }

    @Override
    public List<Map<String, Object>> queryForRootAsMap(Query query, Root root) {
        return query(query, new RootMapConverter(root));
    }

    @Override
    public <R, T extends Throwable> R withSchema(@Nullable String schema, CallableOp<R, T> function) throws T {
        return ScopedValue.where(CURRENT_SCHEMA, schema).call(function);
    }
}
