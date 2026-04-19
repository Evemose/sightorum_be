package com.rorm.viz.dto;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

@JsonClassDescription("""
    Polymorphic coordinate value for any chart axis. The wire shape is
    `string | number`; the value is carried untyped (Object) to preserve
    the exact wire form. The frontend matches the value against the axis
    scale (numeric, time, categorical, etc.).
    
    Examples:
    - `5`            (numeric axis)
    - `12.5`         (numeric axis)
    - `"2026-02-16"` (time axis, ISO date)
    - `"cat-A"`      (categorical axis label)""")
public record AxisCoord(@JsonValue Object raw) {

    public static AxisCoord of(Number value) {
        return new AxisCoord(value);
    }

    public static AxisCoord of(String value) {
        return new AxisCoord(value);
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static AxisCoord fromJson(Object raw) {
        return new AxisCoord(raw);
    }
}
