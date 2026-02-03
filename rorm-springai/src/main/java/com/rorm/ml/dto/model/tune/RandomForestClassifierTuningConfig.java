package com.rorm.ml.dto.model.tune;

import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.rorm.ml.dto.CategoricalSpace;
import com.rorm.ml.dto.IntSpace;
import com.rorm.ml.dto.model.ModelNames;
import com.rorm.ml.dto.model.train.ModelConfig;

/**
 * Tuning configuration for Random Forest Classifier.
 * Defines hyperparameter search spaces for Optuna optimization.
 *
 * @param nEstimators     Search space for number of trees
 * @param maxDepth        Search space for maximum depth
 * @param minSamplesSplit Search space for minimum samples to split
 * @param minSamplesLeaf  Search space for minimum samples at leaf
 * @param maxFeatures     Search space for feature selection strategy
 * @param classWeight     Search space for class weights
 */
@JsonNaming(SnakeCaseStrategy.class)
public record RandomForestClassifierTuningConfig(
    IntSpace nEstimators,
    IntSpace maxDepth,
    IntSpace minSamplesSplit,
    IntSpace minSamplesLeaf,
    CategoricalSpace<String> maxFeatures,
    CategoricalSpace<String> classWeight
) implements TuningModelConfig {

    @Override
    public String modelType() {
        return ModelNames.RANDOM_FOREST_CLASSIFIER;
    }

    @Override
    public boolean requiresTarget() {
        return true;
    }

    @Override
    public ModelConfig.ModelCategory category() {
        return ModelConfig.ModelCategory.CLASSIFICATION;
    }
}
