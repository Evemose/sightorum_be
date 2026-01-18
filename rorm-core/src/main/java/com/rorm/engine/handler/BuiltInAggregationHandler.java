package com.rorm.engine.handler;

import com.rorm.engine.handler.aggregation.*;

/**
 * Marker interface for built-in aggregation handlers.
 * <p>
 * This interface is sealed to enumerate all standard aggregations provided by the library.
 * For custom aggregations, implement {@link CustomAggregationHandler} instead.
 */
public sealed interface BuiltInAggregationHandler extends AggregationHandler permits
    CountAggregation,
    SumAggregation,
    AvgAggregation,
    MinAggregation,
    MaxAggregation,
    StddevPopAggregation,
    StddevSampAggregation,
    VarPopAggregation,
    VarSampAggregation,
    StringAggAggregation,
    ArrayAggAggregation,
    BoolAndAggregation,
    BoolOrAggregation {
}
