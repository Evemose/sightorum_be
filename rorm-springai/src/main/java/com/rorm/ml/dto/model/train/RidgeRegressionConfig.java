package com.rorm.ml.dto.model.train;

import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.rorm.ml.dto.ModelConfig;

/**
 * Configuration for Ridge Regression.
 * Linear regression with L2 regularization.
 *
 * @param alpha        Regularization strength (min: 0, default: 1.0)
 * @param fitIntercept Whether to calculate intercept (default: true)
 * @param maxIter      Maximum iterations for conjugate gradient solver (default: null)
 * @param solver       Solver: "auto", "svd", "cholesky", "lsqr", "sparse_cg", "sag", "saga" (default: "auto")
 */
@JsonNaming(SnakeCaseStrategy.class)
public record RidgeRegressionConfig(Double alpha, Boolean fitIntercept, Integer maxIter, String solver
) implements ModelConfig {

    public RidgeRegressionConfig {
        if (alpha == null) {
            alpha = 1.0;
        }
        if (fitIntercept == null) {
            fitIntercept = true;
        }
        if (solver == null) {
            solver = "auto";
        }
    }

    @Override
    public String modelType() {
        return "ridge_regression";
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
