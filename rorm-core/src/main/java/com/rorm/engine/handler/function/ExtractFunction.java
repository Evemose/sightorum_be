package com.rorm.engine.handler.function;

import com.rorm.engine.handler.BuiltInFunctionHandler;
import com.rorm.engine.handler.TransformContext;
import com.rorm.engine.handler.TypeResolutionContext;
import com.rorm.metamodel.DataType;
import com.rorm.query.Expression;
import org.jooq.DatePart;
import org.jooq.Field;
import org.jooq.impl.DSL;

import java.util.List;

public final class ExtractFunction implements BuiltInFunctionHandler {
    public static final String NAME = "EXTRACT";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public DataType resolveType(List<Expression> args, TypeResolutionContext ctx) {
        return new DataType.NumericType(10, 0);
    }

    @Override
    public Field<?> transform(List<Expression> args, TransformContext ctx) {
        if (args.size() < 2) {
            throw new IllegalArgumentException("EXTRACT requires 2 arguments: date part and source");
        }
        var datePart = extractDatePart(args.get(0));
        var source = ctx.transform(args.get(1));
        return DSL.extract(source, datePart);
    }

    private static DatePart extractDatePart(Expression arg) {
        if (arg instanceof Expression.Literal(var value) && value instanceof String s) {
            return switch (s.toUpperCase()) {
                case "YEAR" -> DatePart.YEAR;
                case "MONTH" -> DatePart.MONTH;
                case "DAY" -> DatePart.DAY;
                case "HOUR" -> DatePart.HOUR;
                case "MINUTE" -> DatePart.MINUTE;
                case "SECOND" -> DatePart.SECOND;
                case "DOW", "DAY_OF_WEEK" -> DatePart.DAY_OF_WEEK;
                case "DOY", "DAY_OF_YEAR" -> DatePart.DAY_OF_YEAR;
                case "WEEK" -> DatePart.ISO_DAY_OF_WEEK;
                case "QUARTER" -> DatePart.QUARTER;
                case "EPOCH" -> DatePart.EPOCH;
                default -> throw new IllegalArgumentException("Unsupported EXTRACT date part: " + s);
            };
        }
        throw new IllegalArgumentException("EXTRACT date part must be a string literal, got: " + arg);
    }
}
