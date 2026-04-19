package com.rorm.query;

import org.jspecify.annotations.Nullable;

/**
 * Defines the window frame for a window function's OVER clause.
 * <p>
 * Examples:
 * <pre>
 * ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW    — running total
 * ROWS BETWEEN 2 PRECEDING AND CURRENT ROW            — 3-row rolling average
 * RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW   — cumulative by value
 * GROUPS BETWEEN 1 PRECEDING AND 1 FOLLOWING           — peer-group window
 * </pre>
 *
 * @param type  frame unit: ROWS, RANGE, or GROUPS
 * @param start lower bound of the frame
 * @param end   upper bound of the frame
 */
public record WindowFrame(
    FrameType type,
    FrameBound start,
    FrameBound end
) {

    public WindowFrame {
        if (type == null) {
            throw new IllegalArgumentException("Frame type is required");
        }
        if (start == null) {
            throw new IllegalArgumentException("Frame start bound is required");
        }
        if (end == null) {
            throw new IllegalArgumentException("Frame end bound is required");
        }
    }

    // Convenience factories for common frame patterns
    public static WindowFrame rowsUnboundedPrecedingToCurrentRow() {
        return new WindowFrame(FrameType.ROWS, FrameBound.unboundedPreceding(), FrameBound.currentRow());
    }

    public static WindowFrame rowsBetween(int preceding, int following) {
        return new WindowFrame(FrameType.ROWS, FrameBound.preceding(preceding), FrameBound.following(following));
    }

    public static WindowFrame rowsPreceding(int n) {
        return new WindowFrame(FrameType.ROWS, FrameBound.preceding(n), FrameBound.currentRow());
    }

    public enum FrameType {
        ROWS,
        RANGE,
        GROUPS
    }

    public enum BoundType {
        UNBOUNDED_PRECEDING,
        N_PRECEDING,
        CURRENT_ROW,
        N_FOLLOWING,
        UNBOUNDED_FOLLOWING
    }

    /**
     * A single endpoint of a window frame.
     *
     * @param type   the bound type
     * @param offset non-null only for N_PRECEDING and N_FOLLOWING; number of rows/range/groups
     */
    public record FrameBound(
        BoundType type,
        @Nullable Integer offset
    ) {

        public FrameBound {
            if (type == null) {
                throw new IllegalArgumentException("Bound type is required");
            }
            if ((type == BoundType.N_PRECEDING || type == BoundType.N_FOLLOWING) && (offset == null || offset < 0)) {
                throw new IllegalArgumentException(type + " requires a non-negative offset, got: " + offset);
            }
        }

        // Convenience factories
        public static FrameBound unboundedPreceding() {
            return new FrameBound(BoundType.UNBOUNDED_PRECEDING, null);
        }

        public static FrameBound preceding(int n) {
            return new FrameBound(BoundType.N_PRECEDING, n);
        }

        public static FrameBound currentRow() {
            return new FrameBound(BoundType.CURRENT_ROW, null);
        }

        public static FrameBound following(int n) {
            return new FrameBound(BoundType.N_FOLLOWING, n);
        }

        public static FrameBound unboundedFollowing() {
            return new FrameBound(BoundType.UNBOUNDED_FOLLOWING, null);
        }
    }
}
