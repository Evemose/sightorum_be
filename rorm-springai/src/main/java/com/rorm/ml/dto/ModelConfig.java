package com.rorm.ml.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeInfo.As;
import com.rorm.ml.dto.model.ModelNames;
import com.rorm.ml.dto.model.TrainingModelSpec;
import com.rorm.ml.dto.model.train.*;

/**
 * Sealed interface hierarchy for ML model configurations.
 * Each model type has a specific implementation with its own parameters,
 * making the configuration type-safe and unambiguous for AI tool calls.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "modelType", include = As.EXISTING_PROPERTY)
@JsonSubTypes({
    // Classification
    @JsonSubTypes.Type(value = RandomForestClassifierConfig.class, name = ModelNames.RANDOM_FOREST_CLASSIFIER),
    @JsonSubTypes.Type(value = LogisticRegressionConfig.class, name = ModelNames.LOGISTIC_REGRESSION),
    @JsonSubTypes.Type(value = SvmClassifierConfig.class, name = ModelNames.SVM_CLASSIFIER),
    @JsonSubTypes.Type(value = LgbmClassifierConfig.class, name = ModelNames.LGBM_CLASSIFIER),
    // Regression
    @JsonSubTypes.Type(value = LinearRegressionConfig.class, name = ModelNames.LINEAR_REGRESSION),
    @JsonSubTypes.Type(value = RidgeRegressionConfig.class, name = ModelNames.RIDGE_REGRESSION),
    @JsonSubTypes.Type(value = RandomForestRegressorConfig.class, name = ModelNames.RANDOM_FOREST_REGRESSOR),
    @JsonSubTypes.Type(value = LgbmRegressorConfig.class, name = ModelNames.LGBM_REGRESSOR),
    // Clustering
    @JsonSubTypes.Type(value = KMeansConfig.class, name = ModelNames.KMEANS),
    @JsonSubTypes.Type(value = DbscanConfig.class, name = ModelNames.DBSCAN),
    @JsonSubTypes.Type(value = AgglomerativeConfig.class, name = ModelNames.AGGLOMERATIVE),
    // Dimensionality Reduction
    @JsonSubTypes.Type(value = PcaConfig.class, name = ModelNames.PCA),
    @JsonSubTypes.Type(value = TsneConfig.class, name = ModelNames.TSNE),
    // Time Series
    @JsonSubTypes.Type(value = ArimaConfig.class, name = ModelNames.ARIMA),
    @JsonSubTypes.Type(value = SarimaxConfig.class, name = ModelNames.SARIMAX)
})
public sealed interface ModelConfig extends TrainingModelSpec permits
    // Classification
    RandomForestClassifierConfig,
    LogisticRegressionConfig,
    SvmClassifierConfig,
    LgbmClassifierConfig,
    // Regression
    LinearRegressionConfig,
    RidgeRegressionConfig,
    RandomForestRegressorConfig,
    LgbmRegressorConfig,
    // Clustering
    KMeansConfig,
    DbscanConfig,
    AgglomerativeConfig,
    // Dimensionality
    PcaConfig,
    TsneConfig,
    // Temporal
    ArimaConfig,
    SarimaxConfig {

    String modelType();

    boolean requiresTarget();

    ModelCategory category();

    enum ModelCategory {
        CLASSIFICATION,
        REGRESSION,
        CLUSTERING,
        DIMENSIONALITY_REDUCTION,
        TEMPORAL
    }
}
