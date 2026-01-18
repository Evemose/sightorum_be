package com.rorm.engine.handler;

import com.rorm.metamodel.DataType;
import com.rorm.query.Expression;
import org.jooq.Field;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Handler for aggregation function expressions.
 * <p>
 * Each implementation handles a specific aggregation (e.g., COUNT, SUM, AVG).
 * Handlers are registered in the {@link HandlerRegistry} and looked up by name.
 * <p>
 * The interface is sealed with built-in implementations, but allows custom
 * extensions via {@link CustomAggregationHandler}.
 */
public sealed interface AggregationHandler
    permits BuiltInAggregationHandler, CustomAggregationHandler {

    /**
     * The aggregation name used for matching in expression trees.
     * Should be uppercase for consistency.
     *
     * @return the aggregation name
     */
    String name();

    /**
     * Resolves the return type of this aggregation given the arguments.
     *
     * @param args the aggregation arguments
     * @param ctx  the type resolution context
     * @return the resolved return type
     */
    @Nullable
    DataType resolveType(List<Expression> args, TypeResolutionContext ctx);

    /**
     * Transforms the aggregation to a jOOQ Field.
     *
     * @param args     the aggregation arguments
     * @param distinct whether this is a DISTINCT aggregation
     * @param ctx      the transform context
     * @return the jOOQ Field representing this aggregation
     */
    Field<?> transform(List<Expression> args, boolean distinct, TransformContext ctx);
}
