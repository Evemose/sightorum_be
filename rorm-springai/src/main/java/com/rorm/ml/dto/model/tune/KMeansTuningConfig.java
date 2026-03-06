package com.rorm.ml.dto.model.tune;

import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.rorm.ml.dto.CategoricalSpace;
import com.rorm.ml.dto.IntSpace;
import com.rorm.ml.dto.ModelConfig;
import com.rorm.ml.dto.model.ModelNames;

@JsonNaming(SnakeCaseStrategy.class)
public record KMeansTuningConfig(
    IntSpace nClusters,
    CategoricalSpace<String> init,
    IntSpace maxIter,
    IntSpace nInit
) implements TuningModelConfig {

    @Override
    public String modelType() {
        return ModelNames.KMEANS;
    }

    @Override
    public boolean requiresTarget() {
        return false;
    }

    @Override
    public ModelConfig.ModelCategory category() {
        return ModelConfig.ModelCategory.CLUSTERING;
    }
}
