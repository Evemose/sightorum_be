package com.rorm.ml.dto.model.train;

import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.rorm.ml.dto.ModelConfig;

/**
 * Configuration for Support Vector Machine Classifier.
 * Finds optimal hyperplane for classification.
 *
 * @param c           Regularization parameter (min: 0.001, default: 1.0)
 * @param kernel      Kernel type: "linear", "poly", "rbf", "sigmoid" (default: "rbf")
 * @param gamma       Kernel coefficient: "scale", "auto", or float value (default: "scale")
 * @param degree      Degree for polynomial kernel (default: 3)
 * @param probability Enable probability estimates (slower training, default: false)
 * @param randomState Random seed for reproducibility (default: 42)
 */
@JsonNaming(SnakeCaseStrategy.class)
public record SvmClassifierConfig(Double c, String kernel, String gamma, Integer degree, Boolean probability,
                                  Integer randomState
) implements ModelConfig {

    public SvmClassifierConfig {
        if (c == null) {
            c = 1.0;
        }
        if (kernel == null) {
            kernel = "rbf";
        }
        if (gamma == null) {
            gamma = "scale";
        }
        if (degree == null) {
            degree = 3;
        }
        if (probability == null) {
            probability = false;
        }
        if (randomState == null) {
            randomState = 42;
        }
    }

    @Override
    public String modelType() {
        return "svm_classifier";
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
