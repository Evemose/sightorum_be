package com.rorm.engine.handler.window;

import com.rorm.engine.handler.BuiltInWindowFunctionHandler;
import com.rorm.engine.handler.TransformContext;
import com.rorm.query.WindowSpec;
import org.jooq.Field;
import org.jooq.SortField;
import org.jooq.WindowOverStep;

/**
 * Base class for window function handlers with common window specification handling.
 */
public abstract non-sealed class AbstractWindowFunction implements BuiltInWindowFunctionHandler {

    protected Field<?>[] transformPartitionFields(WindowSpec spec, TransformContext ctx) {
        if (spec.partitionBy() == null || spec.partitionBy().isEmpty()) {
            return null;
        }
        return spec.partitionBy().stream()
            .map(ctx::transform)
            .toArray(Field[]::new);
    }

    protected SortField<?>[] transformOrderFields(WindowSpec spec, TransformContext ctx) {
        if (spec.orderBy() == null || spec.orderBy().isEmpty()) {
            return null;
        }
        return spec.orderBy().stream()
            .map(ob -> {
                var f = ctx.transform(ob.expression());
                return ob.ascending() ? f.asc() : f.desc();
            })
            .toArray(SortField[]::new);
    }

    protected <T> Field<?> applyWindowSpec(WindowOverStep<T> func, Field<?>[] partition, SortField<?>[] order) {
        if (partition != null && order != null) {
            return func.over().partitionBy(partition).orderBy(order);
        } else if (partition != null) {
            return func.over().partitionBy(partition);
        } else if (order != null) {
            return func.over().orderBy(order);
        }
        return func.over();
    }
}
