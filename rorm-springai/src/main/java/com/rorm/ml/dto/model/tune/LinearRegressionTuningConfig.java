package com.rorm.ml.dto.model.tune;

import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.rorm.ml.dto.CategoricalSpace;
import com.rorm.ml.dto.model.ModelNames;
import com.rorm.ml.dto.model.train.ModelConfig;

@JsonNaming(SnakeCaseStrategy.class)
public record LinearRegressionTuningConfig(
    CategoricalSpace<Boolean> fitIntercept,
    CategoricalSpace<Boolean> positive
) implements TuningModelConfig {

    @Override
    public String modelType() {
        return ModelNames.LINEAR_REGRESSION;
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
