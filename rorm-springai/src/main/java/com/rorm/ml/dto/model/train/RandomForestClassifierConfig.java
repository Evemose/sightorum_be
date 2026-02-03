package com.rorm.ml.dto.model.train;

import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.rorm.ml.dto.model.ModelNames;

/**
 * Configuration for Random Forest Classifier.
 * Ensemble of decision trees with bagging for classification.
 *
 * @param nEstimators     Number of trees in the forest (1-10000, default: 100)
 * @param maxDepth        Maximum depth of trees (null = unlimited)
 * @param minSamplesSplit Minimum samples required to split a node (min: 2, default: 2)
 * @param minSamplesLeaf  Minimum samples required at a leaf node (min: 1, default: 1)
 * @param maxFeatures     Features to consider for best split: "sqrt", "log2", "auto" (default: "sqrt")
 * @param randomState     Random seed for reproducibility (default: 42)
 * @param nJobs           Parallel jobs (-1 = all cores, default: -1)
 * @param classWeight     Class weights: "balanced" or "balanced_subsample" (null = none)
 */
@JsonNaming(SnakeCaseStrategy.class)
public record RandomForestClassifierConfig(
    Integer nEstimators,
    Integer maxDepth,
    Integer minSamplesSplit,
    Integer minSamplesLeaf,
    String maxFeatures,
    Integer randomState,
    Integer nJobs,
    String classWeight
) implements ModelConfig {

    public RandomForestClassifierConfig {
        if (nEstimators == null) {
            nEstimators = 100;
        }
        if (minSamplesSplit == null) {
            minSamplesSplit = 2;
        }
        if (minSamplesLeaf == null) {
            minSamplesLeaf = 1;
        }
        if (maxFeatures == null) {
            maxFeatures = "sqrt";
        }
        if (randomState == null) {
            randomState = 42;
        }
        if (nJobs == null) {
            nJobs = -1;
        }
    }

    @Override
    public String modelType() {
        return ModelNames.RANDOM_FOREST_CLASSIFIER;
    }

    @Override
    public boolean requiresTarget() {
        return true;
    }

    @Override
    public ModelCategory category() {
        return ModelCategory.CLASSIFICATION;
    }
}
