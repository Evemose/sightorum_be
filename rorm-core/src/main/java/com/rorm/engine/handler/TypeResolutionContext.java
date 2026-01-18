package com.rorm.engine.handler;

import com.rorm.metamodel.DataType;
import com.rorm.metamodel.Root;
import com.rorm.query.Expression;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Context provided to handlers during type resolution.
 */
public interface TypeResolutionContext {

    /**
     * The root entity of the current query context.
     */
    Root fromRoot();

    /**
     * Resolves the type of the first argument, or returns a default.
     *
     * @param args        the argument list
     * @param defaultType the default type if args is empty
     * @return the resolved type or default
     */
    @Nullable
    default DataType resolveFirstArg(List<Expression> args, @Nullable DataType defaultType) {
        if (args == null || args.isEmpty()) {
            return defaultType;
        }
        return resolve(args.getFirst());
    }

    /**
     * Resolves the type of a nested expression.
     *
     * @param expression the expression to resolve
     * @return the resolved DataType, or null for NULL literals
     */
    @Nullable
    DataType resolve(Expression expression);

    /**
     * Resolves the type of the second argument, or returns a default.
     *
     * @param args        the argument list
     * @param defaultType the default type if args has fewer than 2 elements
     * @return the resolved type or default
     */
    @Nullable
    default DataType resolveSecondArg(List<Expression> args, @Nullable DataType defaultType) {
        if (args == null || args.size() < 2) {
            return defaultType;
        }
        return resolve(args.get(1));
    }

    /**
     * Promotes two numeric types to a common type.
     *
     * @param left  the left type
     * @param right the right type
     * @return the promoted type
     */
    default DataType promoteNumeric(@Nullable DataType left, @Nullable DataType right) {
        if (left instanceof DataType.NumericType(
            int precision1, int scale1
        ) && right instanceof DataType.NumericType(int precision, int scale)) {
            return new DataType.NumericType(
                Math.max(precision1, precision),
                Math.max(scale1, scale)
            );
        }
        if (left instanceof DataType.NumericType) {
            return left;
        }
        if (right instanceof DataType.NumericType) {
            return right;
        }
        return new DataType.NumericType(19, 6);
    }
}
