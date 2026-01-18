package com.rorm.query;

import com.rorm.engine.handler.window.*;

/**
 * Standard window functions supported by the query system.
 * <p>
 * Each constant provides a type-safe identifier that maps to the corresponding
 * window function handler.
 */
public enum StandardWindowFunction {
    // Ranking functions
    ROW_NUMBER(RowNumberWindow.NAME),
    RANK(RankWindow.NAME),
    DENSE_RANK(DenseRankWindow.NAME),
    NTILE(NtileWindow.NAME),
    PERCENT_RANK(PercentRankWindow.NAME),
    CUME_DIST(CumeDistWindow.NAME),

    // Value functions
    LAG(LagWindow.NAME),
    LEAD(LeadWindow.NAME),
    FIRST_VALUE(FirstValueWindow.NAME),
    LAST_VALUE(LastValueWindow.NAME),
    NTH_VALUE(NthValueWindow.NAME),

    // Aggregate window functions
    COUNT(CountWindow.NAME),
    SUM(SumWindow.NAME),
    AVG(AvgWindow.NAME),
    MIN(MinWindow.NAME),
    MAX(MaxWindow.NAME);

    private final String identifier;

    StandardWindowFunction(String identifier) {
        this.identifier = identifier;
    }

    /**
     * Returns the window function name identifier used for handler lookup.
     *
     * @return the window function name
     */
    public String identifier() {
        return identifier;
    }
}
