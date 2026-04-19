package com.rorm.viz.dto.data;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

@JsonClassDescription("""
    Polymorphic time coordinate accepted by time-series payloads. The
    wire shape is `string | number`:
    - ISO-8601 string (preferred): '2026-02-16' or '2026-02-16T08:00:00Z'.
    - Epoch milliseconds number: e.g. 1739664000000.
    
    The value is carried untyped (Object) to preserve the exact wire form.
    Helpers `ofIso(...)` and `ofEpochMs(...)` produce well-formed values.""")
public record TimeCoord(@JsonValue Object raw) {

    public static TimeCoord ofIso(String iso) {
        return new TimeCoord(iso);
    }

    public static TimeCoord ofEpochMs(long epochMs) {
        return new TimeCoord(epochMs);
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static TimeCoord fromJson(Object raw) {
        return new TimeCoord(raw);
    }
}
