package com.rorm.ml.dto.model.train;

import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * Configuration for Linear Regression.
 * Ordinary least squares linear regression model.
 *
 * @param fitIntercept Whether to calculate intercept (default: true)
 */
@JsonNaming(SnakeCaseStrategy.class)
public record LinearRegressionConfig(Boolean fitIntercept
) implements ModelConfig {

    public LinearRegressionConfig {
        if (fitIntercept == null) {
            fitIntercept = true;
        }
    }

    @Override
    public String modelType() {
        return "linear_regression";
    }

    @Override
    public boolean requiresTarget() {
        return true;
    }

    @Override
    public ModelCategory category() {
        return ModelCategory.REGRESSION;
    }
}
