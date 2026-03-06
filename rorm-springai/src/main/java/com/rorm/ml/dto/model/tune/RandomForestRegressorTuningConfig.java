package com.rorm.ml.dto.model.tune;

import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.rorm.ml.dto.CategoricalSpace;
import com.rorm.ml.dto.IntSpace;
import com.rorm.ml.dto.ModelConfig;
import com.rorm.ml.dto.model.ModelNames;

@JsonNaming(SnakeCaseStrategy.class)
public record RandomForestRegressorTuningConfig(
    IntSpace nEstimators,
    IntSpace maxDepth,
    IntSpace minSamplesSplit,
    IntSpace minSamplesLeaf,
    CategoricalSpace<String> maxFeatures
) implements TuningModelConfig {

    @Override
    public String modelType() {
        return ModelNames.RANDOM_FOREST_REGRESSOR;
    }

    @Override
    public boolean requiresTarget() {
        return true;
    }

    @Override
    public ModelConfig.ModelCategory category() {
        return ModelConfig.ModelCategory.REGRESSION;
    }
}
