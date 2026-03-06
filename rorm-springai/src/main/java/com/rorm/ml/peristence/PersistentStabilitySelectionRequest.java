package com.rorm.ml.peristence;

import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import org.jspecify.annotations.Nullable;

import java.util.List;

@Embeddable
public record PersistentStabilitySelectionRequest(
    String reason,
    String sql,
    String targetColumn,
    @Nullable String problemType,
    @ElementCollection List<String> featureColumns,
    int bootstrapRuns,
    double sampleFraction,
    double correlationThreshold,
    int polynomialDegree,
    @Nullable Integer selectionTopK
) {}
