package com.rorm.engine.handler.window;

import com.rorm.engine.handler.BuiltInWindowFunctionHandler;
import com.rorm.engine.handler.TransformContext;
import com.rorm.query.WindowFrame;
import com.rorm.query.WindowFrame.FrameBound;
import com.rorm.query.WindowSpec;
import org.jooq.*;

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

    /**
     * Apply window specification without frame (backward compatible).
     */
    protected <T> Field<?> applyWindowSpec(WindowOverStep<T> func, Field<?>[] partition, SortField<?>[] order) {
        return applyWindowSpec(func, partition, order, null);
    }

    /**
     * Apply window specification with optional frame.
     */
    protected <T> Field<?> applyWindowSpec(WindowOverStep<T> func, Field<?>[] partition, SortField<?>[] order, WindowFrame frame) {
        var overStep = func.over();

        // Apply PARTITION BY
        WindowOrderByStep<T> partitioned = (partition != null)
            ? overStep.partitionBy(partition)
            : overStep;

        // Apply ORDER BY
        if (order != null) {
            var ordered = partitioned.orderBy(order);
            if (frame != null) {
                return applyFrame(ordered, frame);
            }
            return ordered;
        }

        // No ORDER BY
        if (frame != null) {
            // Frame without ORDER BY — jOOQ requires order before frame,
            // but SQL allows it (defaults to RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW).
            // We pass an empty orderBy to get to WindowRowsStep.
            return applyFrame((WindowRowsStep<T>) partitioned.orderBy(), frame);
        }

        if (partition != null) {
            return partitioned;
        }

        return overStep;
    }

    @SuppressWarnings("unchecked")
    private <T> Field<T> applyFrame(WindowRowsStep<T> step, WindowFrame frame) {
        WindowRowsAndStep<T> afterStart = switch (frame.type()) {
            case ROWS -> applyRowsStart(step, frame.start());
            case RANGE -> applyRangeStart(step, frame.start());
            case GROUPS -> applyGroupsStart(step, frame.start());
        };
        return (Field<T>) applyEndBound(afterStart, frame.end());
    }

    // --- ROWS start bound ---
    private <T> WindowRowsAndStep<T> applyRowsStart(WindowRowsStep<T> step, FrameBound bound) {
        return switch (bound.type()) {
            case UNBOUNDED_PRECEDING -> step.rowsBetweenUnboundedPreceding();
            case N_PRECEDING -> step.rowsBetweenPreceding(bound.offset());
            case CURRENT_ROW -> step.rowsBetweenCurrentRow();
            case N_FOLLOWING -> step.rowsBetweenFollowing(bound.offset());
            case UNBOUNDED_FOLLOWING -> step.rowsBetweenUnboundedFollowing();
        };
    }

    // --- RANGE start bound ---
    private <T> WindowRowsAndStep<T> applyRangeStart(WindowRowsStep<T> step, FrameBound bound) {
        return switch (bound.type()) {
            case UNBOUNDED_PRECEDING -> step.rangeBetweenUnboundedPreceding();
            case N_PRECEDING -> step.rangeBetweenPreceding(bound.offset());
            case CURRENT_ROW -> step.rangeBetweenCurrentRow();
            case N_FOLLOWING -> step.rangeBetweenFollowing(bound.offset());
            case UNBOUNDED_FOLLOWING -> step.rangeBetweenUnboundedFollowing();
        };
    }

    // --- GROUPS start bound ---
    private <T> WindowRowsAndStep<T> applyGroupsStart(WindowRowsStep<T> step, FrameBound bound) {
        return switch (bound.type()) {
            case UNBOUNDED_PRECEDING -> step.groupsBetweenUnboundedPreceding();
            case N_PRECEDING -> step.groupsBetweenPreceding(bound.offset());
            case CURRENT_ROW -> step.groupsBetweenCurrentRow();
            case N_FOLLOWING -> step.groupsBetweenFollowing(bound.offset());
            case UNBOUNDED_FOLLOWING -> step.groupsBetweenUnboundedFollowing();
        };
    }

    // --- End bound (same for all frame types) ---
    private <T> WindowExcludeStep<T> applyEndBound(WindowRowsAndStep<T> step, FrameBound bound) {
        return switch (bound.type()) {
            case UNBOUNDED_PRECEDING -> step.andUnboundedPreceding();
            case N_PRECEDING -> step.andPreceding(bound.offset());
            case CURRENT_ROW -> step.andCurrentRow();
            case N_FOLLOWING -> step.andFollowing(bound.offset());
            case UNBOUNDED_FOLLOWING -> step.andUnboundedFollowing();
        };
    }
}
