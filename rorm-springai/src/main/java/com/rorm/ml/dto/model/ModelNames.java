package com.rorm.ml.dto.model;

/**
 * Constants for ML model type names.
 * Used across train and tune configurations to avoid string literal duplication.
 */
public interface ModelNames {

    // Classification
    String RANDOM_FOREST_CLASSIFIER = "random_forest_classifier";
    String LOGISTIC_REGRESSION = "logistic_regression";
    String SVM_CLASSIFIER = "svm_classifier";
    String LGBM_CLASSIFIER = "lgbm_classifier";

    // Regression
    String LINEAR_REGRESSION = "linear_regression";
    String RIDGE_REGRESSION = "ridge_regression";
    String RANDOM_FOREST_REGRESSOR = "random_forest_regressor";
    String LGBM_REGRESSOR = "lgbm_regressor";

    // Clustering
    String KMEANS = "kmeans";
    String DBSCAN = "dbscan";
    String AGGLOMERATIVE = "agglomerative";

    // Dimensionality Reduction
    String PCA = "pca";
    String TSNE = "tsne";

    // Time Series
    String ARIMA = "arima";
    String SARIMAX = "sarimax";
}
