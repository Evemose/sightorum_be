package com.rorm.ml.dto.model.train;

import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.rorm.ml.dto.ModelConfig;

/**
 * Configuration for K-Means Clustering.
 * Partitions data into k clusters based on centroid distance.
 *
 * @param nClusters   Number of clusters to find (2-1000, default: 8)
 * @param init        Initialization method: "k-means++", "random" (default: "k-means++")
 * @param nInit       Number of initializations to run (default: 10)
 * @param maxIter     Maximum iterations per run (default: 300)
 * @param randomState Random seed for reproducibility (default: 42)
 */
@JsonNaming(SnakeCaseStrategy.class)
public record KMeansConfig(Integer nClusters, String init, Integer nInit, Integer maxIter, Integer randomState
) implements ModelConfig {

    public KMeansConfig {
        if (nClusters == null) {
            nClusters = 8;
        }
        if (init == null) {
            init = "k-means++";
        }
        if (nInit == null) {
            nInit = 10;
        }
        if (maxIter == null) {
            maxIter = 300;
        }
        if (randomState == null) {
            randomState = 42;
        }
    }

    @Override
    public String modelType() {
        return "kmeans";
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
