package com.rorm.ml.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Sealed hierarchy for hyperparameter search spaces used in tuning.
 * Maps to Python Optuna parameter sampling: int, float, loguniform, categorical.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = IntSpace.class, name = "int"),
    @JsonSubTypes.Type(value = FloatSpace.class, name = "float"),
    @JsonSubTypes.Type(value = CategoricalSpace.class, name = "categorical")
})
public sealed interface ParamSpace permits IntSpace, FloatSpace, CategoricalSpace {
    String type();
}

