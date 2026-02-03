package com.rorm.ml.dto.model.train;

import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * Configuration for Agglomerative (Hierarchical) Clustering.
 * Recursively merges clusters based on linkage criteria.
 *
 * @param nClusters Number of clusters to find (2-1000, default: 8)
 * @param linkage   Linkage criterion: "ward", "complete", "average", "single" (default: "ward")
 * @param metric    Distance metric: "euclidean", "manhattan", "cosine", "l1", "l2" (default: "euclidean", ignored if linkage=ward)
 */
@JsonNaming(SnakeCaseStrategy.class)
public record AgglomerativeConfig(Integer nClusters, String linkage, String metric
) implements ModelConfig {

    public AgglomerativeConfig {
        if (nClusters == null) {
            nClusters = 8;
        }
        if (linkage == null) {
            linkage = "ward";
        }
        if (metric == null) {
            metric = "euclidean";
        }
    }

    @Override
    public String modelType() {
        return "agglomerative";
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
