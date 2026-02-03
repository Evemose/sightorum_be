package com.rorm.ml.dto.model.tune;

import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.rorm.ml.dto.CategoricalSpace;
import com.rorm.ml.dto.IntSpace;
import com.rorm.ml.dto.model.ModelNames;
import com.rorm.ml.dto.model.train.ModelConfig;

@JsonNaming(SnakeCaseStrategy.class)
public record AgglomerativeTuningConfig(
    IntSpace nClusters,
    CategoricalSpace<String> linkage,
    CategoricalSpace<String> metric
) implements TuningModelConfig {

    @Override
    public String modelType() {
        return ModelNames.AGGLOMERATIVE;
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
