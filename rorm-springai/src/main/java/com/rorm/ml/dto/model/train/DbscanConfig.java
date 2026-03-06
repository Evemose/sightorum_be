package com.rorm.ml.dto.model.train;

import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.rorm.ml.dto.ModelConfig;

/**
 * Configuration for DBSCAN Clustering.
 * Density-Based Spatial Clustering of Applications with Noise.
 * Finds clusters of arbitrary shape and identifies outliers.
 *
 * @param eps        Maximum distance between samples in same neighborhood (default: 0.5)
 * @param minSamples Minimum samples in a neighborhood for core point (default: 5)
 * @param metric     Distance metric: "euclidean", "manhattan", "cosine" (default: "euclidean")
 * @param algorithm  Nearest neighbors algorithm: "auto", "ball_tree", "kd_tree", "brute" (default: "auto")
 */
@JsonNaming(SnakeCaseStrategy.class)
public record DbscanConfig(Double eps, Integer minSamples, String metric, String algorithm
) implements ModelConfig {

    public DbscanConfig {
        if (eps == null) {
            eps = 0.5;
        }
        if (minSamples == null) {
            minSamples = 5;
        }
        if (metric == null) {
            metric = "euclidean";
        }
        if (algorithm == null) {
            algorithm = "auto";
        }
    }

    @Override
    public String modelType() {
        return "dbscan";
    }

    @Override
    public boolean requiresTarget() {
        return false;
    }

    @Override
    public ModelCategory category() {
        return ModelCategory.CLUSTERING;
    }
}
