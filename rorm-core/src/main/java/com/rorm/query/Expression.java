package com.rorm.query;

import com.rorm.query.Expression.*;
import com.rorm.query.Operator.BinaryOperator;
import com.rorm.query.Operator.TernaryOperator;
import com.rorm.query.Operator.UnaryOperator;

import java.util.List;

public sealed interface Expression permits
    Path,
    FunctionCall,
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
    }

    record WindowFunction(
        String functionName,
        List<Expression> arguments,
        WindowSpec windowSpec
    ) implements Expression {
    }

    record Literal(Object value) implements Expression {
    }

    record BinaryExpression(
        Expression left,
        BinaryOperator operator,
        Expression right
    ) implements Expression {
    }

    record UnaryExpression(
        UnaryOperator operator,
        Expression operand
    ) implements Expression {
    }

    record TernaryExpression(
        Expression first,
        TernaryOperator operator,
        Expression second,
        Expression third
    ) implements Expression {
    }

}
