package com.rorm.ml.dto.model.tune;

import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.rorm.ml.dto.IntSpace;
import com.rorm.ml.dto.ModelConfig;
import com.rorm.ml.dto.model.ModelNames;

@JsonNaming(SnakeCaseStrategy.class)
public record SarimaxTuningConfig(
    IntSpace p,
    IntSpace d,
    IntSpace q,
    IntSpace seasonalP,
    IntSpace seasonalD,
    IntSpace seasonalQ,
    IntSpace seasonalPeriods
) implements TuningModelConfig {

    @Override
    public String modelType() {
        return ModelNames.SARIMAX;
    }

    @Override
    public boolean requiresTarget() {
        return true;
    }

    @Override
    public ModelConfig.ModelCategory category() {
        return ModelConfig.ModelCategory.TEMPORAL;
    }
}
