package com.rorm.ml.dto;

import java.util.List;

/**
 * Categorical parameter space for hyperparameter tuning.
 *
 * @param choices List of discrete choices
 */
public record CategoricalSpace<T>(
    List<T> choices
) implements ParamSpace {

    @Override
    public String type() {
        return "categorical";
    }
}
