package com.rorm.engine.handler.function;

import com.rorm.engine.handler.BuiltInFunctionHandler;
import com.rorm.engine.handler.TransformContext;
import com.rorm.engine.handler.TypeResolutionContext;
import com.rorm.metamodel.DataType;
import com.rorm.query.Expression;
import org.jooq.Field;
import org.jooq.impl.SQLDataType;

import java.util.List;

public final class CastFunction implements BuiltInFunctionHandler {
    public static final String NAME = "CAST";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public DataType resolveType(List<Expression> args, TypeResolutionContext ctx) {
        if (args.size() < 2) {
            throw new IllegalArgumentException("CAST requires 2 arguments: expression and target type");
        }
        return resolveTargetDataType(extractTypeName(args.get(1)));
    }

    private static DataType resolveTargetDataType(String typeName) {
        return switch (typeName) {
            case "DOUBLE", "DOUBLE PRECISION", "FLOAT8", "FLOAT", "REAL", "FLOAT4",
                 "NUMERIC", "DECIMAL" -> new DataType.NumericType(15, 6);
            case "INTEGER", "INT", "INT4", "BIGINT", "INT8",
                 "SMALLINT", "INT2" -> new DataType.NumericType(10, 0);
            case "VARCHAR", "TEXT", "STRING" -> new DataType.StringType();
            case "BOOLEAN", "BOOL" -> new DataType.BooleanType();
            case "DATE" -> new DataType.DateType();
            case "TIME" -> new DataType.TimeType();
            case "TIMESTAMP" -> new DataType.DateTimeType();
            default -> throw new IllegalArgumentException("Unsupported CAST target type: " + typeName);
        };
    }

    private static String extractTypeName(Expression typeArg) {
        if (typeArg instanceof Expression.Literal(var value) && value instanceof String s) {
            return s.toUpperCase();
        }
        throw new IllegalArgumentException("CAST target type must be a string literal, got: " + typeArg);
    }

    @Override
    public Field<?> transform(List<Expression> args, TransformContext ctx) {
        if (args.size() < 2) {
            throw new IllegalArgumentException("CAST requires 2 arguments: expression and target type");
        }
        var source = ctx.transform(args.get(0));
        var targetType = mapToSqlDataType(extractTypeName(args.get(1)));
        return source.cast(targetType);
    }

    private static org.jooq.DataType<?> mapToSqlDataType(String typeName) {
        return switch (typeName) {
            case "DOUBLE", "DOUBLE PRECISION", "FLOAT8" -> SQLDataType.DOUBLE;
            case "FLOAT", "REAL", "FLOAT4" -> SQLDataType.REAL;
            case "INTEGER", "INT", "INT4" -> SQLDataType.INTEGER;
            case "BIGINT", "INT8" -> SQLDataType.BIGINT;
            case "SMALLINT", "INT2" -> SQLDataType.SMALLINT;
            case "NUMERIC", "DECIMAL" -> SQLDataType.NUMERIC;
            case "VARCHAR", "TEXT", "STRING" -> SQLDataType.VARCHAR;
            case "BOOLEAN", "BOOL" -> SQLDataType.BOOLEAN;
            case "DATE" -> SQLDataType.LOCALDATE;
            case "TIME" -> SQLDataType.LOCALTIME;
            case "TIMESTAMP" -> SQLDataType.LOCALDATETIME;
            default -> throw new IllegalArgumentException("Unsupported CAST target type: " + typeName);
        };
    }
}
