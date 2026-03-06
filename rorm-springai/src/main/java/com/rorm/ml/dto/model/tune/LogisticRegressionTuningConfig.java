package com.rorm.ml.dto.model.tune;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.rorm.ml.dto.CategoricalSpace;
import com.rorm.ml.dto.FloatSpace;
import com.rorm.ml.dto.IntSpace;
import com.rorm.ml.dto.ModelConfig;
import com.rorm.ml.dto.model.ModelNames;

@JsonNaming(SnakeCaseStrategy.class)
public record LogisticRegressionTuningConfig(
    @JsonProperty("C") FloatSpace c,
    CategoricalSpace<String> penalty,
    CategoricalSpace<String> solver,
    IntSpace maxIter
) implements TuningModelConfig {

    @Override
    public String modelType() {
        return ModelNames.LOGISTIC_REGRESSION;
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
