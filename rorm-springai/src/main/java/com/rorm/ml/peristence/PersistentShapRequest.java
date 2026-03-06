package com.rorm.ml.peristence;

import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import org.jspecify.annotations.Nullable;

import java.util.List;

@Embeddable
public record PersistentShapRequest(
    String reason,
    String runId,
    @Nullable @ElementCollection List<String> features,
    int nBins,
    int nBreakpoints
) {}
