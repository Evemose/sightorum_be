package com.rorm.engine.handler.function;

import com.rorm.engine.handler.BuiltInFunctionHandler;
import com.rorm.engine.handler.TransformContext;
import com.rorm.engine.handler.TypeResolutionContext;
import com.rorm.metamodel.DataType;
import com.rorm.query.Expression;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.jooq.types.DayToSecond;
import org.jooq.types.YearToMonth;

import java.util.List;

/**
 * INTERVAL(amount, unit) — creates an interval literal for date/time arithmetic.
 * <p>
 * Supported units: YEAR, MONTH, DAY, HOUR, MINUTE, SECOND.
 * <p>
 * Example: {@code INTERVAL(3, 'DAY')} → {@code INTERVAL '3' DAY}
 * <p>
 * Used with ADD/SUBTRACT operators: {@code date_column + INTERVAL(7, 'DAY')}
 */
public final class IntervalFunction implements BuiltInFunctionHandler {
    public static final String NAME = "INTERVAL";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public DataType resolveType(List<Expression> args, TypeResolutionContext ctx) {
        return new DataType.IntervalType();
    }

    @Override
    public Field<?> transform(List<Expression> args, TransformContext ctx) {
        if (args.size() < 2) {
            throw new IllegalArgumentException("INTERVAL requires 2 arguments: amount and unit");
        }
        var amountExpr = args.get(0);
        var unitExpr = args.get(1);

        int amount = extractAmount(amountExpr);
        var unit = extractUnit(unitExpr);

        return switch (unit) {
            case "YEAR", "YEARS" -> DSL.val(new YearToMonth(amount, 0));
            case "MONTH", "MONTHS" -> DSL.val(new YearToMonth(0, amount));
            case "DAY", "DAYS" -> DSL.val(new DayToSecond(amount));
            case "HOUR", "HOURS" -> DSL.val(new DayToSecond(0, amount));
            case "MINUTE", "MINUTES" -> DSL.val(new DayToSecond(0, 0, amount));
            case "SECOND", "SECONDS" -> DSL.val(new DayToSecond(0, 0, 0, amount));
            default -> throw new IllegalArgumentException("Unsupported interval unit: " + unit
                                                          + ". Supported: YEAR(S), MONTH(S), DAY(S), HOUR(S), MINUTE(S), SECOND(S)");
        };
    }

    private int extractAmount(Expression amountExpr) {
        if (amountExpr instanceof Expression.Literal(var value)) {
            if (value instanceof Number n) {
                return n.intValue();
            }
            throw new IllegalArgumentException("INTERVAL amount must be a number, got: " + value);
        }
        throw new IllegalArgumentException("INTERVAL amount must be a literal number");
    }

    private String extractUnit(Expression unitExpr) {
        if (unitExpr instanceof Expression.Literal(var value) && value instanceof String s) {
            return s.toUpperCase();
        }
        throw new IllegalArgumentException("INTERVAL unit must be a string literal (YEAR, MONTH, DAY, HOUR, MINUTE, SECOND)");
    }
}
