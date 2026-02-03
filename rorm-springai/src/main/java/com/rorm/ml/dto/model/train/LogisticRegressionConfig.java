package com.rorm.ml.dto.model.train;

import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * Configuration for Logistic Regression.
 * Linear model for binary and multiclass classification.
 *
 * @param c           Inverse regularization strength (min: 0.001, default: 1.0)
 * @param penalty     Regularization: "l1", "l2", "elasticnet", "none" (default: "l2")
 * @param solver      Optimization algorithm: "lbfgs", "liblinear", "saga" (default: "lbfgs")
 * @param maxIter     Maximum iterations for solver convergence (default: 100)
 * @param randomState Random seed for reproducibility (default: 42)
 */
@JsonNaming(SnakeCaseStrategy.class)
public record LogisticRegressionConfig(
    Double c,
    String penalty,
    String solver,
    Integer maxIter,
    Integer randomState
) implements ModelConfig {

    public LogisticRegressionConfig {
        if (c == null) {
            c = 1.0;
        }
        if (penalty == null) {
            penalty = "l2";
        }
        if (solver == null) {
            solver = "lbfgs";
        }
        if (maxIter == null) {
            maxIter = 100;
        }
        if (randomState == null) {
            randomState = 42;
        }
    }

    @Override
    public String modelType() {
        return "logistic_regression";
    }

    @Override
    public boolean requiresTarget() {
        return true;
    }

    @Override
    public ModelCategory category() {
        return ModelCategory.CLASSIFICATION;
    }
}
