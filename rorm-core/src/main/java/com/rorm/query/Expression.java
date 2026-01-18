package com.rorm.query;

import com.rorm.query.Expression.*;

import java.util.List;

public sealed interface Expression permits
    Path,
    FunctionCall,
    Aggregation,
    WindowFunction,
    Literal,
    BinaryExpression,
    UnaryExpression,
    TernaryExpression,
    Subquery,
    OuterRef {

    record FunctionCall(
        String functionName,
        List<Expression> arguments
    ) implements Expression {

        public static FunctionCall of(StandardFunction function, Expression... args) {
            return new FunctionCall(function.identifier(), List.of(args));
        }

        public static FunctionCall of(String functionName, Expression... args) {
            return new FunctionCall(functionName, List.of(args));
        }
    }

    record Aggregation(
        String functionName,
        List<Expression> arguments,
        boolean distinct
    ) implements Expression {

        public static Aggregation of(StandardAggregation aggregation, Expression arg) {
            return new Aggregation(aggregation.identifier(), List.of(arg), false);
        }

        public static Aggregation of(StandardAggregation aggregation, Expression arg, boolean distinct) {
            return new Aggregation(aggregation.identifier(), List.of(arg), distinct);
        }

        public static Aggregation of(String functionName, Expression arg) {
            return new Aggregation(functionName, List.of(arg), false);
        }

        public static Aggregation count() {
            return new Aggregation("COUNT", List.of(new Literal("*")), false);
        }

        public static Aggregation countDistinct(Expression arg) {
            return new Aggregation("COUNT", List.of(arg), true);
        }
    }

    record WindowFunction(
        String functionName,
        List<Expression> arguments,
        WindowSpec windowSpec
    ) implements Expression {

        public static WindowFunction of(StandardWindowFunction function, WindowSpec spec, Expression... args) {
            return new WindowFunction(function.identifier(), List.of(args), spec);
        }

        public static WindowFunction of(String functionName, WindowSpec spec, Expression... args) {
            return new WindowFunction(functionName, List.of(args), spec);
        }
    }

    record Literal(Object value) implements Expression {

        public static Literal of(Object value) {
            return new Literal(value);
        }
    }

    record BinaryExpression(
        Expression left,
        String operator,
        Expression right
    ) implements Expression {

        public static BinaryExpression of(Expression left, String operator, Expression right) {
            return new BinaryExpression(left, operator, right);
        }

        // Convenience factory methods for common operations
        public static BinaryExpression eq(Expression left, Expression right) {
            return of(left, StandardOperator.Binary.EQUALS, right);
        }

        public static BinaryExpression of(Expression left, StandardOperator.Binary op, Expression right) {
            return new BinaryExpression(left, op.identifier(), right);
        }

        public static BinaryExpression ne(Expression left, Expression right) {
            return of(left, StandardOperator.Binary.NOT_EQUALS, right);
        }

        public static BinaryExpression gt(Expression left, Expression right) {
            return of(left, StandardOperator.Binary.GREATER_THAN, right);
        }

        public static BinaryExpression ge(Expression left, Expression right) {
            return of(left, StandardOperator.Binary.GREATER_THAN_OR_EQUAL, right);
        }

        public static BinaryExpression lt(Expression left, Expression right) {
            return of(left, StandardOperator.Binary.LESS_THAN, right);
        }

        public static BinaryExpression le(Expression left, Expression right) {
            return of(left, StandardOperator.Binary.LESS_THAN_OR_EQUAL, right);
        }

        public static BinaryExpression and(Expression left, Expression right) {
            return of(left, StandardOperator.Binary.AND, right);
        }

        public static BinaryExpression or(Expression left, Expression right) {
            return of(left, StandardOperator.Binary.OR, right);
        }

        public static BinaryExpression like(Expression left, Expression right) {
            return of(left, StandardOperator.Binary.LIKE, right);
        }

        public static BinaryExpression add(Expression left, Expression right) {
            return of(left, StandardOperator.Binary.ADD, right);
        }

        public static BinaryExpression subtract(Expression left, Expression right) {
            return of(left, StandardOperator.Binary.SUBTRACT, right);
        }

        public static BinaryExpression multiply(Expression left, Expression right) {
            return of(left, StandardOperator.Binary.MULTIPLY, right);
        }

        public static BinaryExpression divide(Expression left, Expression right) {
            return of(left, StandardOperator.Binary.DIVIDE, right);
        }
    }

    record UnaryExpression(
        String operator,
        Expression operand
    ) implements Expression {

        public static UnaryExpression of(String operator, Expression operand) {
            return new UnaryExpression(operator, operand);
        }

        // Convenience factory methods
        public static UnaryExpression isNull(Expression operand) {
            return of(StandardOperator.Unary.IS_NULL, operand);
        }

        public static UnaryExpression of(StandardOperator.Unary op, Expression operand) {
            return new UnaryExpression(op.identifier(), operand);
        }

        public static UnaryExpression isNotNull(Expression operand) {
            return of(StandardOperator.Unary.IS_NOT_NULL, operand);
        }

        public static UnaryExpression not(Expression operand) {
            return of(StandardOperator.Unary.NOT, operand);
        }

        public static UnaryExpression negate(Expression operand) {
            return of(StandardOperator.Unary.NEGATE, operand);
        }
    }

    record TernaryExpression(
        Expression first,
        String operator,
        Expression second,
        Expression third
    ) implements Expression {

        public static TernaryExpression of(Expression first, String operator, Expression second, Expression third) {
            return new TernaryExpression(first, operator, second, third);
        }

        // Convenience factory methods
        public static TernaryExpression between(Expression value, Expression low, Expression high) {
            return of(value, StandardOperator.Ternary.BETWEEN, low, high);
        }

        public static TernaryExpression of(Expression first, StandardOperator.Ternary op, Expression second, Expression third) {
            return new TernaryExpression(first, op.identifier(), second, third);
        }

        public static TernaryExpression notBetween(Expression value, Expression low, Expression high) {
            return of(value, StandardOperator.Ternary.NOT_BETWEEN, low, high);
        }
    }

}
