package com.rorm.testutil;

import com.rorm.engine.handler.HandlerRegistry;
import com.rorm.engine.handler.aggregation.*;
import com.rorm.engine.handler.function.*;
import com.rorm.engine.handler.operator.binary.*;
import com.rorm.engine.handler.operator.ternary.BetweenOperator;
import com.rorm.engine.handler.operator.unary.*;
import com.rorm.engine.handler.window.*;

import java.util.List;

/**
 * Test utility for creating a HandlerRegistry with all built-in handlers.
 */
public final class TestHandlerRegistry {

    private TestHandlerRegistry() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Creates a HandlerRegistry with all currently present built-in handlers.
     *
     * @return a fully configured HandlerRegistry
     */
    public static HandlerRegistry createWithAllBuiltIns() {
        return HandlerRegistry.builder()
            // Binary operators
            .binaryOperators(List.of(
                new EqualsOperator(),
                new GreaterThanOperator(),
                new GreaterThanOrEqualOperator(),
                new LessThanOperator(),
                new LessThanOrEqualOperator(),
                new LikeOperator(),
                new InOperator(),
                new AddOperator(),
                new SubtractOperator(),
                new MultiplyOperator(),
                new DivideOperator(),
                new ModuloOperator(),
                new AndOperator(),
                new OrOperator()
            ))
            // Unary operators
            .unaryOperators(List.of(
                new IsNullOperator(),
                new IsNotNullOperator(),
                new IsTrueOperator(),
                new IsFalseOperator(),
                new NegateOperator(),
                new NotOperator()
            ))
            // Ternary operators
            .ternaryOperators(List.of(
                new BetweenOperator()
            ))
            // Aggregations
            .aggregations(List.of(
                new CountAggregation(),
                new SumAggregation(),
                new AvgAggregation(),
                new MinAggregation(),
                new MaxAggregation(),
                new StddevPopAggregation(),
                new StddevSampAggregation(),
                new VarPopAggregation(),
                new VarSampAggregation(),
                new StringAggAggregation(),
                new ArrayAggAggregation(),
                new BoolAndAggregation(),
                new BoolOrAggregation()
            ))
            // Functions
            .functions(List.of(
                // Conditional functions
                new CoalesceFunction(),
                new NullIfFunction(),
                new GreatestFunction(),
                new LeastFunction(),
                new CaseFunction(),
                // Date/time functions
                new NowFunction(),
                new CurrentDateFunction(),
                new CurrentTimeFunction(),
                new DateTruncFunction(),
                new ExtractFunction(),
                // String functions
                new ConcatFunction(),
                new LowerFunction(),
                new UpperFunction(),
                new LengthFunction(),
                new TrimFunction(),
                new LTrimFunction(),
                new RTrimFunction(),
                new LeftFunction(),
                new RightFunction(),
                new SubstringFunction(),
                new ReplaceFunction(),
                new PositionFunction(),
                new ReverseFunction(),
                new RepeatFunction(),
                new LPadFunction(),
                new RPadFunction(),
                new InitCapFunction(),
                // Numeric functions
                new AbsFunction(),
                new CeilFunction(),
                new FloorFunction(),
                new RoundFunction(),
                new TruncFunction(),
                new ModFunction(),
                new PowerFunction(),
                new SqrtFunction(),
                new ExpFunction(),
                new LnFunction(),
                new LogFunction(),
                new SignFunction()
            ))
            // Window functions
            .windowFunctions(List.of(
                new RowNumberWindow(),
                new RankWindow(),
                new DenseRankWindow(),
                new PercentRankWindow(),
                new CumeDistWindow(),
                new NtileWindow(),
                new LagWindow(),
                new LeadWindow(),
                new FirstValueWindow(),
                new LastValueWindow(),
                new NthValueWindow(),
                new CountWindow(),
                new SumWindow(),
                new AvgWindow(),
                new MinWindow(),
                new MaxWindow()
            ))
            .build();
    }
}
