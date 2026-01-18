package com.rorm.query;

import com.rorm.engine.handler.aggregation.*;

/**
 * Standard aggregation functions supported by the query system.
 * <p>
 * Each constant provides a type-safe identifier that maps to the corresponding
 * aggregation handler. Use these with {@link Expression.Aggregation#of(StandardAggregation, Expression)}
 * for compile-time safe aggregation references.
 */
public enum StandardAggregation {
    COUNT(CountAggregation.NAME),
    SUM(SumAggregation.NAME),
    AVG(AvgAggregation.NAME),
    MIN(MinAggregation.NAME),
    MAX(MaxAggregation.NAME),
    STDDEV_POP(StddevPopAggregation.NAME),
    STDDEV_SAMP(StddevSampAggregation.NAME),
    VAR_POP(VarPopAggregation.NAME),
    VAR_SAMP(VarSampAggregation.NAME),
    STRING_AGG(StringAggAggregation.NAME),
    ARRAY_AGG(ArrayAggAggregation.NAME),
    BOOL_AND(BoolAndAggregation.NAME),
    BOOL_OR(BoolOrAggregation.NAME);

    private final String identifier;

    StandardAggregation(String identifier) {
        this.identifier = identifier;
    }

    /**
     * Returns the aggregation name identifier used for handler lookup.
     *
     * @return the aggregation name
     */
    public String identifier() {
        return identifier;
    }
}
