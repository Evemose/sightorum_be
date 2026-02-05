package com.rorm.ml.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.rorm.ml.dto.model.tune.TuningModelConfig;
import lombok.Builder;

import java.util.List;
import java.util.Map;

/**
 * Request for hyperparameter tuning followed by training.
 * Serializes to match Python FastAPI endpoint structure:
 * {
 * "request": { model_type, model_name, datasource, target_column, feature_columns, model_params },
 * "param_space": { ... tuning config params ... },
 * "tuning_config": { n_trials, max_tuning_time, metric }
 * }
 */
@Builder
@JsonNaming(SnakeCaseStrategy.class)
public record TuningJobRequest(
    @JsonIgnore String reason,
    @JsonIgnore String furtherInstructions,
    BaseTrainingRequest request,
    TuningModelConfig paramSpace,
    TuningConfig tuningConfig
) implements TrainingRequest {
    public TuningJobRequest {
        if (tuningConfig == null) {
            tuningConfig = TuningConfig.defaults();
        }
    }

    /**
     * Inner class representing the base training request.
     */
    @Builder
    @JsonNaming(SnakeCaseStrategy.class)
    public record BaseTrainingRequest(
        String modelType,
        String modelName,
        DatasourceConfig datasource,
        String targetColumn,
        List<String> featureColumns,
        Map<String, Object> modelParams
    ) {
        public BaseTrainingRequest {
            if (modelParams == null) {
                modelParams = Map.of();
            }
        }
    }
}
