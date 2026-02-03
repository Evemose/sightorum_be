package com.rorm.ml.dto.model.train;

import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * Configuration for Principal Component Analysis.
 * Linear dimensionality reduction using SVD.
 *
 * @param nComponents Number of components to keep (1-min(n_samples, n_features), null = all, default: null)
 * @param whiten      Whether to whiten components (unit variance, default: false)
 * @param svdSolver   SVD solver: "auto", "full", "arpack", "randomized" (default: "auto")
 * @param randomState Random seed for reproducibility when using randomized solver (default: 42)
 */
@JsonNaming(SnakeCaseStrategy.class)
public record PcaConfig(Integer nComponents, Boolean whiten, String svdSolver, Integer randomState
) implements ModelConfig {

    public PcaConfig {
        if (whiten == null) {
            whiten = false;
        }
        if (svdSolver == null) {
            svdSolver = "auto";
        }
        if (randomState == null) {
            randomState = 42;
        }
    }

    @Override
    public String modelType() {
        return "pca";
    }

    @Override
    public boolean requiresTarget() {
        return false;
    }

    @Override
    public ModelCategory category() {
        return ModelCategory.DIMENSIONALITY_REDUCTION;
    }
}
