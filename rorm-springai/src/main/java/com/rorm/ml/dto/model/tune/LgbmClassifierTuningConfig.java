package com.rorm.ml.dto.model.tune;

import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.rorm.ml.dto.FloatSpace;
import com.rorm.ml.dto.IntSpace;
import com.rorm.ml.dto.model.ModelNames;
import com.rorm.ml.dto.model.train.ModelConfig;

@JsonNaming(SnakeCaseStrategy.class)
public record LgbmClassifierTuningConfig(
    IntSpace nEstimators,
    IntSpace maxDepth,
    FloatSpace learningRate,
    IntSpace numLeaves,
    IntSpace minChildSamples,
    FloatSpace subsample,
    FloatSpace colsampleBytree
) implements TuningModelConfig {

    @Override
    public String modelType() {
        return ModelNames.LGBM_CLASSIFIER;
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
