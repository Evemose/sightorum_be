package com.rorm.query;

public sealed interface Operator permits
    Operator.UnaryOperator,
    Operator.BinaryOperator,
    Operator.TernaryOperator {

    enum UnaryOperator implements Operator {
        IS_NULL,
        IS_NOT_NULL,
        IS_TRUE,
        IS_FALSE,
        NEGATE,
        NOT,
        EXISTS
    }

    enum BinaryOperator implements Operator {
        EQUALS,
        GREATER_THAN,
        GREATER_THAN_OR_EQUAL,
        LESS_THAN,
        LESS_THAN_OR_EQUAL,
        LIKE,
        IN,
        ADD,
        SUBTRACT,
        MULTIPLY,
        DIVIDE,
        MODULO,
        AND,
        OR
    }

    enum TernaryOperator implements Operator {
        BETWEEN
    }
}
