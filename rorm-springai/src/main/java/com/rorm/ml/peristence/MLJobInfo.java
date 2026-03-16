package com.rorm.ml.peristence;

import org.jspecify.annotations.Nullable;

import java.util.UUID;

public record MLJobInfo(
    UUID jobId,
    String reason,
    @Nullable String furtherInstructions
) {
}
