package com.rorm.engine.handler;

import com.rorm.engine.handler.aggregation.*;
import com.rorm.engine.handler.function.*;
import com.rorm.engine.handler.operator.binary.*;
import com.rorm.engine.handler.operator.ternary.BetweenOperator;
import com.rorm.engine.handler.operator.ternary.NotBetweenOperator;
import com.rorm.engine.handler.operator.unary.*;
import com.rorm.engine.handler.window.*;

/**
 * Utility class for registering all built-in handlers.
 */
final class BuiltInHandlers {

    private BuiltInHandlers() {
    }

    static void registerAll(HandlerRegistry.HandlerRegistryBuilder builder) {
        registerFunctions(builder);
        registerAggregations(builder);
        registerWindowFunctions(builder);
        registerUnaryOperators(builder);
        registerBinaryOperators(builder);
        registerTernaryOperators(builder);
    }

    private static void registerFunctions(HandlerRegistry.HandlerRegistryBuilder builder) {
        // String functions
        builder.function(new UpperFunction());
        builder.function(new LowerFunction());
        builder.function(new TrimFunction());
        builder.function(new LTrimFunction());
        builder.function(new RTrimFunction());
        builder.function(new ConcatFunction());
        builder.function(new SubstringFunction());
        builder.function(new ReplaceFunction());
        builder.function(new LeftFunction());
        builder.function(new RightFunction());
        builder.function(new ReverseFunction());
        builder.function(new LPadFunction());
        builder.function(new RPadFunction());
        builder.function(new InitCapFunction());
        builder.function(new RepeatFunction());
        builder.function(new LengthFunction());
        builder.function(new PositionFunction());

        // Numeric functions
        builder.function(new AbsFunction());
        builder.function(new RoundFunction());
        builder.function(new FloorFunction());
        builder.function(new CeilFunction());
        builder.function(new TruncFunction());
        builder.function(new SignFunction());
        builder.function(new ModFunction());
        builder.function(new SqrtFunction());
        builder.function(new PowerFunction());
        builder.function(new ExpFunction());
        builder.function(new LnFunction());
        builder.function(new LogFunction());

        // Date/Time functions
        builder.function(new NowFunction());
        builder.function(new CurrentDateFunction());
        builder.function(new CurrentTimeFunction());
        builder.function(new DateTruncFunction());
        builder.function(new ExtractFunction());

        // Conditional functions
        builder.function(new CoalesceFunction());
        builder.function(new NullIfFunction());
        builder.function(new GreatestFunction());
        builder.function(new LeastFunction());
        builder.function(new CaseFunction());
    }

    private static void registerAggregations(HandlerRegistry.HandlerRegistryBuilder builder) {
        builder.aggregation(new CountAggregation());
        builder.aggregation(new SumAggregation());
        builder.aggregation(new AvgAggregation());
        builder.aggregation(new MinAggregation());
        builder.aggregation(new MaxAggregation());
        builder.aggregation(new StddevPopAggregation());
        builder.aggregation(new StddevSampAggregation());
        builder.aggregation(new VarPopAggregation());
        builder.aggregation(new VarSampAggregation());
        builder.aggregation(new StringAggAggregation());
        builder.aggregation(new ArrayAggAggregation());
        builder.aggregation(new BoolAndAggregation());
        builder.aggregation(new BoolOrAggregation());
    }

    private static void registerWindowFunctions(HandlerRegistry.HandlerRegistryBuilder builder) {
        // Ranking functions
        builder.windowFunction(new RowNumberWindow());
        builder.windowFunction(new RankWindow());
        builder.windowFunction(new DenseRankWindow());
        builder.windowFunction(new NtileWindow());
        builder.windowFunction(new PercentRankWindow());
        builder.windowFunction(new CumeDistWindow());

        // Value functions
        builder.windowFunction(new LagWindow());
        builder.windowFunction(new LeadWindow());
        builder.windowFunction(new FirstValueWindow());
        builder.windowFunction(new LastValueWindow());
        builder.windowFunction(new NthValueWindow());

        // Aggregate window functions
        builder.windowFunction(new CountWindow());
        builder.windowFunction(new SumWindow());
        builder.windowFunction(new AvgWindow());
        builder.windowFunction(new MinWindow());
        builder.windowFunction(new MaxWindow());
    }

    private static void registerUnaryOperators(HandlerRegistry.HandlerRegistryBuilder builder) {
        builder.unaryOperator(new IsNullOperator());
        builder.unaryOperator(new IsNotNullOperator());
        builder.unaryOperator(new IsTrueOperator());
        builder.unaryOperator(new IsFalseOperator());
        builder.unaryOperator(new NegateOperator());
        builder.unaryOperator(new NotOperator());
    }

    private static void registerBinaryOperators(HandlerRegistry.HandlerRegistryBuilder builder) {
        // Comparison operators
        builder.binaryOperator(new EqualsOperator());
        builder.binaryOperator(new NotEqualsOperator());
        builder.binaryOperator(new GreaterThanOperator());
        builder.binaryOperator(new GreaterThanOrEqualOperator());
        builder.binaryOperator(new LessThanOperator());
        builder.binaryOperator(new LessThanOrEqualOperator());
        builder.binaryOperator(new LikeOperator());
        builder.binaryOperator(new NotLikeOperator());
        builder.binaryOperator(new InOperator());
        builder.binaryOperator(new NotInOperator());

        // Arithmetic operators
        builder.binaryOperator(new AddOperator());
        builder.binaryOperator(new SubtractOperator());
        builder.binaryOperator(new MultiplyOperator());
        builder.binaryOperator(new DivideOperator());
        builder.binaryOperator(new ModuloOperator());

        // Logical operators
        builder.binaryOperator(new AndOperator());
        builder.binaryOperator(new OrOperator());
    }

    private static void registerTernaryOperators(HandlerRegistry.HandlerRegistryBuilder builder) {
        builder.ternaryOperator(new BetweenOperator());
        builder.ternaryOperator(new NotBetweenOperator());
    }
}
