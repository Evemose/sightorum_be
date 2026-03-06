package com.rorm.ml.dto.model;

import com.rorm.ml.dto.ModelConfig;
import com.rorm.ml.dto.model.tune.TuningModelConfig;

public sealed interface TrainingModelSpec permits ModelConfig, TuningModelConfig {
}
