package com.rorm.ml.stream;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.RequiredArgsConstructor;

import java.util.HashMap;
import java.util.Map;

@RequiredArgsConstructor
public enum JobEventType {
    JOB_STARTED("job.started"),
    JOB_PROGRESS("job.progress"),
    JOB_SUCCESS("job.success"),
    JOB_FAILED("job.failed"),
    VALIDATION_FAILED("job.validation_failed");

    private static final Map<String, JobEventType> VALUE_MAP = new HashMap<>();

    static {
        for (var type : values()) {
            VALUE_MAP.put(type.value, type);
        }
    }

    private final String value;

    @JsonCreator
    public static JobEventType fromValue(String value) {
        var type = VALUE_MAP.get(value);
        if (type == null) {
            throw new IllegalArgumentException("Unknown JobEventType value: " + value);
        }
        return type;
    }

    @JsonValue
    public String value() {
        return value;
    }
}
