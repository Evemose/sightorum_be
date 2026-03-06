package com.rorm.ml.dto.model.tune;

import com.rorm.ml.dto.IntSpace;
import com.rorm.ml.dto.ModelConfig;
import com.rorm.ml.dto.model.ModelNames;

public record ArimaTuningConfig(
    IntSpace p,
    IntSpace d,
    IntSpace q
) implements TuningModelConfig {

    @Override
    public String modelType() {
        return ModelNames.ARIMA;
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
