package com.rorm.ml.dto.model.tune;

import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.rorm.ml.dto.CategoricalSpace;
import com.rorm.ml.dto.IntSpace;
import com.rorm.ml.dto.model.ModelNames;
import com.rorm.ml.dto.model.train.ModelConfig;

@JsonNaming(SnakeCaseStrategy.class)
public record PcaTuningConfig(
    IntSpace nComponents,
    CategoricalSpace<Boolean> whiten
) implements TuningModelConfig {

    @Override
    public String modelType() {
        return ModelNames.PCA;
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
