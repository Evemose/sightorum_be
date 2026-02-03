package com.rorm.ml.dto.model.train;

import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * Configuration for t-SNE (t-Distributed Stochastic Neighbor Embedding).
 * Non-linear dimensionality reduction for visualization.
 * Best for visualizing high-dimensional data in 2D or 3D.
 *
 * @param nComponents  Number of dimensions in output (1-3, default: 2)
 * @param perplexity   Balance between local and global structure (5-50, default: 30.0)
 * @param learningRate Learning rate (default: "auto")
 * @param nIter        Maximum optimization iterations (250-10000, default: 1000)
 * @param metric       Distance metric: "euclidean", "manhattan", "cosine" (default: "euclidean")
 * @param init         Initialization: "pca" (more stable), "random" (default: "pca")
 * @param randomState  Random seed for reproducibility (default: 42)
 */
@JsonNaming(SnakeCaseStrategy.class)
public record TsneConfig(Integer nComponents, Double perplexity, String learningRate, Integer nIter, String metric,
                         String init, Integer randomState
) implements ModelConfig {

    public TsneConfig {
        if (nComponents == null) {
            nComponents = 2;
        }
        if (perplexity == null) {
            perplexity = 30.0;
        }
        if (learningRate == null) {
            learningRate = "auto";
        }
        if (nIter == null) {
            nIter = 1000;
        }
        if (metric == null) {
            metric = "euclidean";
        }
        if (init == null) {
            init = "pca";
        }
        if (randomState == null) {
            randomState = 42;
        }
    }

    @Override
    public String modelType() {
        return "tsne";
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
