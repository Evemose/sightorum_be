package com.rorm.query;

import com.rorm.engine.handler.function.*;

/**
 * Standard functions supported by the query system.
 * <p>
 * Each constant provides a type-safe identifier that maps to the corresponding
 * function handler. Use these with {@link Expression.FunctionCall#of(StandardFunction, Expression...)}
 * for compile-time safe function references.
 */
public enum StandardFunction {
    // String functions
    UPPER(UpperFunction.NAME),
    LOWER(LowerFunction.NAME),
    TRIM(TrimFunction.NAME),
    LTRIM(LTrimFunction.NAME),
    RTRIM(RTrimFunction.NAME),
    CONCAT(ConcatFunction.NAME),
    SUBSTRING(SubstringFunction.NAME),
    REPLACE(ReplaceFunction.NAME),
    LEFT(LeftFunction.NAME),
    RIGHT(RightFunction.NAME),
    REVERSE(ReverseFunction.NAME),
    LPAD(LPadFunction.NAME),
    RPAD(RPadFunction.NAME),
    INITCAP(InitCapFunction.NAME),
    REPEAT(RepeatFunction.NAME),
    LENGTH(LengthFunction.NAME),
    POSITION(PositionFunction.NAME),

    // Numeric functions
    ABS(AbsFunction.NAME),
    ROUND(RoundFunction.NAME),
    FLOOR(FloorFunction.NAME),
    CEIL(CeilFunction.NAME),
    TRUNC(TruncFunction.NAME),
    SIGN(SignFunction.NAME),
    MOD(ModFunction.NAME),
    SQRT(SqrtFunction.NAME),
    POWER(PowerFunction.NAME),
    EXP(ExpFunction.NAME),
    LN(LnFunction.NAME),
    LOG(LogFunction.NAME),

    // Date/Time functions
    NOW(NowFunction.NAME),
    CURRENT_DATE(CurrentDateFunction.NAME),
    CURRENT_TIME(CurrentTimeFunction.NAME),
    DATE_TRUNC(DateTruncFunction.NAME),
    EXTRACT(ExtractFunction.NAME),

    // Conditional functions
    COALESCE(CoalesceFunction.NAME),
    NULLIF(NullIfFunction.NAME),
    GREATEST(GreatestFunction.NAME),
    LEAST(LeastFunction.NAME),
    CASE(CaseFunction.NAME);

    private final String identifier;

    StandardFunction(String identifier) {
        this.identifier = identifier;
    }

    /**
     * Returns the function name identifier used for handler lookup.
     *
     * @return the function name
     */
    public String identifier() {
        return identifier;
    }
}
