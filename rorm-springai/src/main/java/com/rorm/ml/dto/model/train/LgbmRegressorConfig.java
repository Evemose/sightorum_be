package com.rorm.ml.dto.model.train;

import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * Configuration for LightGBM Regressor.
 * Gradient boosting framework using tree-based learning algorithms.
 * Optimized for speed and efficiency.
 *
 * @param nEstimators     Number of boosting iterations (1-10000, default: 100)
 * @param learningRate    Boosting learning rate (0.001-1.0, default: 0.1)
 * @param maxDepth        Maximum tree depth (-1 = unlimited, default: -1)
 * @param numLeaves       Maximum leaves per tree (2-131072, default: 31)
 * @param minChildSamples Minimum samples in a leaf (min: 1, default: 20)
 * @param subsample       Subsample ratio of training data (0.1-1.0, default: 1.0)
 * @param colsampleBytree Subsample ratio of features (0.1-1.0, default: 1.0)
 * @param regAlpha        L1 regularization term (min: 0, default: 0.0)
 * @param regLambda       L2 regularization term (min: 0, default: 0.0)
 * @param randomState     Random seed for reproducibility (default: 42)
 * @param nJobs           Parallel threads (-1 = all cores, default: -1)
 */
@JsonNaming(SnakeCaseStrategy.class)
public record LgbmRegressorConfig(Integer nEstimators, Double learningRate, Integer maxDepth, Integer numLeaves,
                                  Integer minChildSamples, Double subsample, Double colsampleBytree, Double regAlpha,
                                  Double regLambda, Integer randomState, Integer nJobs
) implements ModelConfig {

    public LgbmRegressorConfig {
        if (nEstimators == null) {
            nEstimators = 100;
        }
        if (learningRate == null) {
            learningRate = 0.1;
        }
        if (maxDepth == null) {
            maxDepth = -1;
        }
        if (numLeaves == null) {
            numLeaves = 31;
        }
        if (minChildSamples == null) {
            minChildSamples = 20;
        }
        if (subsample == null) {
            subsample = 1.0;
        }
        if (colsampleBytree == null) {
            colsampleBytree = 1.0;
        }
        if (regAlpha == null) {
            regAlpha = 0.0;
        }
        if (regLambda == null) {
            regLambda = 0.0;
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
        return "lgbm_regressor";
    }

    @Override
    public boolean requiresTarget() {
        return true;
    }

    @Override
    public ModelCategory category() {
        return ModelCategory.REGRESSION;
    }
}
