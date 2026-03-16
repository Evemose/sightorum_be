package com.rorm.ml.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.rorm.ml.peristence.MLJobType;
import lombok.Builder;

import java.util.List;

@Builder
@JsonNaming(SnakeCaseStrategy.class)
public record TrainingJobRequest(
    @JsonIgnore String reason,
    @JsonIgnore String furtherInstructions,
    DatasourceConfig datasource,
    String targetColumn,
    List<String> featureColumns,
    @JsonIgnore ModelConfig modelConfig
) implements AsyncJobRequest {

    @JsonProperty
    public String modelType() {
        return modelConfig.modelType();
    }

    @JsonProperty
    public ModelConfig modelParams() {
        return modelConfig;
    }

    @Override
    @JsonIgnore
    public MLJobType jobType() {
        return MLJobType.TRAINING;
    }
}
