package com.rorm.ml.dto;

public sealed interface TrainingRequest permits TrainingJobRequest, TuningJobRequest {
}
