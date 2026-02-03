package com.rorm.ml.dto.model.tune;

import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.rorm.ml.dto.FloatSpace;
import com.rorm.ml.dto.IntSpace;
import com.rorm.ml.dto.model.ModelNames;
import com.rorm.ml.dto.model.train.ModelConfig;

@JsonNaming(SnakeCaseStrategy.class)
public record TsneTuningConfig(
    IntSpace nComponents,
    FloatSpace perplexity,
    FloatSpace learningRate,
    IntSpace nIter
) implements TuningModelConfig {

    @Override
    public String modelType() {
        return ModelNames.TSNE;
    }

    @Override
    public boolean requiresTarget() {
        return false;
    }

    @Override
    public ModelConfig.ModelCategory category() {
        return ModelConfig.ModelCategory.DIMENSIONALITY_REDUCTION;
    }
}
