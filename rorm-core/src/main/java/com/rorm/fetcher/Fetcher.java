package com.rorm.fetcher;

import com.rorm.metamodel.Root;
import com.rorm.query.Query;
import org.jspecify.annotations.Nullable;

import java.lang.ScopedValue.CallableOp;
import java.util.List;
import java.util.Map;

/**
 * Facade for executing queries and fetching results with type conversion.
 */
public interface Fetcher {

    /**
     * Execute a query and convert each row using the provided converter.
     */
    <T> List<T> query(Query query, RowConverter<T> converter);

    /**
     * Execute a query and convert each row to the specified type.
     */
    default <T> List<T> queryForType(Query query, Class<T> resultType) {
        return queryForType(query, () -> resultType);
    }

    <T> List<T> queryForType(Query query, TypeRef<T> typeRef);

    /**
     * Execute a query for a Root entity and convert using Root-aware converter.
     */
    <T> List<T> queryForRoot(Query query, Root root, Class<T> resultType);

    /**
     * Execute a query for a Root entity and convert to Map.
     */
    List<Map<String, Object>> queryForRootAsMap(Query query, Root root);

    <R, T extends Throwable> R withSchema(@Nullable String schema, CallableOp<R, T> function) throws T;
}
