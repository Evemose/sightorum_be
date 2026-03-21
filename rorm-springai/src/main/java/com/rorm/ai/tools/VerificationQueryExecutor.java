package com.rorm.ai.tools;

import com.rorm.ai.RormToolContext;
import com.rorm.dto.dense.DenseQueryDto;
import com.rorm.fetcher.Fetcher;
import com.rorm.mapper.DenseQueryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
class VerificationQueryExecutor {

    private final Fetcher fetcher;
    private final DenseQueryMapper denseQueryMapper;

    static double numVal(Map<String, Object> row, String key) {
        var value = row.get(key);
        if (value == null) {
            return 0.0;
        }
        if (value instanceof Number num) {
            return num.doubleValue();
        }
        throw new IllegalArgumentException("'" + key + "' is not numeric: " + value);
    }

    static long longVal(Map<String, Object> row, String key) {
        var value = row.get(key);
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number num) {
            return num.longValue();
        }
        throw new IllegalArgumentException("'" + key + "' is not numeric: " + value);
    }

    Map<String, Object> executeSingle(DenseQueryDto queryDTO, RormToolContext ctx) {
        var results = execute(queryDTO, ctx);
        if (results.isEmpty()) {
            throw new IllegalStateException("Query returned no results");
        }
        return results.getFirst();
    }

    List<Map<String, Object>> execute(DenseQueryDto queryDTO, RormToolContext ctx) {
        var query = denseQueryMapper.toEntity(queryDTO, ctx.modelSpace());
        return fetcher.withSchema(ctx.schema(), () ->
            fetcher.queryForType(query, () -> {
                @SuppressWarnings("unchecked")
                var clazz = (Class<Map<String, Object>>) (Class<?>) Map.class;
                return clazz;
            })
        );
    }
}
