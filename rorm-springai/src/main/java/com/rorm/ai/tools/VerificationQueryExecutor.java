package com.rorm.ai.tools;

import com.rorm.ai.RormToolContext;
import com.rorm.dto.dense.DenseExpressionDto;
import com.rorm.dto.dense.DenseQueryDto;
import com.rorm.engine.ExpressionTypeResolver;
import com.rorm.fetcher.Fetcher;
import com.rorm.mapper.DenseQueryMapper;
import com.rorm.metamodel.DataType;
import com.rorm.metamodel.Root;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
class VerificationQueryExecutor {

    private final Fetcher fetcher;
    private final DenseQueryMapper denseQueryMapper;
    private final ExpressionTypeResolver typeResolver;

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

    void requireCorrCompatible(DenseExpressionDto expr, String paramName,
                               String rootName, RormToolContext ctx) {
        var root = findRoot(rootName, ctx);
        var domainExpr = denseQueryMapper.expressionToEntity(expr, ctx.modelSpace(), rootName);
        var dataType = typeResolver.resolveWithRoot(domainExpr, root);
        if (dataType instanceof DataType.StringType || dataType instanceof DataType.CategorcialType) {
            throw new CorrIncompatibleTypeException(paramName, dataType);
        }
    }

    private static Root findRoot(String rootName, RormToolContext ctx) {
        return ctx.modelSpace().roots().stream()
            .filter(r -> r.primaryTableName().equals(rootName))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown root: " + rootName));
    }

    static final class CorrIncompatibleTypeException extends RuntimeException {
        CorrIncompatibleTypeException(String paramName, DataType actualType) {
            super(("Parameter '%s' resolves to %s which cannot be used in correlation/regression. " +
                   "Only numeric, temporal, and boolean types are supported. " +
                   "For categorical variables, use a pattern that groups by them " +
                   "(e.g. PROXY_ABSORPTION, TREATMENT_DIRECTION) or wrap in a numeric expression.")
                .formatted(paramName, actualType.getClass().getSimpleName()));
        }
    }
}
