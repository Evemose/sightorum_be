package com.rorm.ml.peristence;

import org.jspecify.annotations.Nullable;

public record MLJobInfo(
    String jobId,
    String reason,
    @Nullable String furtherInstructions
) {
}
