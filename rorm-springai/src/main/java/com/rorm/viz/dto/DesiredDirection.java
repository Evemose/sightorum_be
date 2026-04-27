package com.rorm.viz.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonClassDescription;

@JsonClassDescription("""
    Polarity hint controlling which numeric direction the frontend treats
    as favorable. Drives the cyan ("good") / red ("bad") mapping on
    sign-encoded charts (DivergingBar, DivergingLollipop, Dumbbell,
    SlopeChart, KpiCard, KpiCardPair) and structural extremes on sorted
    bar/column charts (HorizontalBar, VerticalBar, ColumnOverTime).
    
    Defaults to `higher` when omitted. Charts without a sign-encoded
    dimension (Sankey, Treemap, Histogram, Radar, WrappedYearOverYear)
    accept the value but ignore it.
    
    Values:
    - higher: increases are favorable (e.g. revenue, on-time rate).
    - lower: decreases are favorable (e.g. error rate, cost, latency).""")
public enum DesiredDirection {
    @JsonAlias("higher") HIGHER,
    @JsonAlias("lower") LOWER
}
