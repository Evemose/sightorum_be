package com.rorm.ml.stream;

import com.fasterxml.jackson.annotation.JsonCreator;
import lombok.RequiredArgsConstructor;

import java.util.HashMap;
import java.util.Map;

@RequiredArgsConstructor
public enum TrainingEventType {
    TRAINING_STARTED("training.started"),
    TRAINING_PROGRESS("training.progress"),
    TRAINING_SUCCESS("training.success"),
    TRAINING_FAILED("training.failed"),
    VALIDATION_FAILED("training.validation_failed");

    private static final Map<String, TrainingEventType> VALUE_MAP = new HashMap<>();

    static {
        for (var type : values()) {
            VALUE_MAP.put(type.value, type);
        }
    }

    private final String value;

    @JsonCreator
    public static TrainingEventType fromValue(String value) {
        var type = VALUE_MAP.get(value);
        if (type == null) {
            throw new IllegalArgumentException("Unknown TrainingEventType value: " + value);
        }
        return type;
    }
}
