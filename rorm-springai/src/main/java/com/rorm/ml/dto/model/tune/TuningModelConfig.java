package com.rorm.ml.dto.model.tune;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeInfo.As;
import com.rorm.ml.dto.ModelConfig;
import com.rorm.ml.dto.model.ModelNames;
import com.rorm.ml.dto.model.TrainingModelSpec;

/**
 * Sealed interface hierarchy for ML model tuning configurations.
 * Defines hyperparameter search spaces for tune-then-train workflow.
 * Each model type has a specific implementation with parameter spaces instead of fixed values.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "modelType", include = As.EXISTING_PROPERTY)
@JsonSubTypes({
    // Classification
    @JsonSubTypes.Type(value = RandomForestClassifierTuningConfig.class, name = ModelNames.RANDOM_FOREST_CLASSIFIER),
    @JsonSubTypes.Type(value = LogisticRegressionTuningConfig.class, name = ModelNames.LOGISTIC_REGRESSION),
    @JsonSubTypes.Type(value = SvmClassifierTuningConfig.class, name = ModelNames.SVM_CLASSIFIER),
    @JsonSubTypes.Type(value = LgbmClassifierTuningConfig.class, name = ModelNames.LGBM_CLASSIFIER),
    // Regression
    @JsonSubTypes.Type(value = LinearRegressionTuningConfig.class, name = ModelNames.LINEAR_REGRESSION),
    @JsonSubTypes.Type(value = RidgeRegressionTuningConfig.class, name = ModelNames.RIDGE_REGRESSION),
    @JsonSubTypes.Type(value = RandomForestRegressorTuningConfig.class, name = ModelNames.RANDOM_FOREST_REGRESSOR),
    @JsonSubTypes.Type(value = LgbmRegressorTuningConfig.class, name = ModelNames.LGBM_REGRESSOR),
    // Clustering
    @JsonSubTypes.Type(value = KMeansTuningConfig.class, name = ModelNames.KMEANS),
    @JsonSubTypes.Type(value = DbscanTuningConfig.class, name = ModelNames.DBSCAN),
    @JsonSubTypes.Type(value = AgglomerativeTuningConfig.class, name = ModelNames.AGGLOMERATIVE),
    // Dimensionality Reduction
    @JsonSubTypes.Type(value = PcaTuningConfig.class, name = ModelNames.PCA),
    @JsonSubTypes.Type(value = TsneTuningConfig.class, name = ModelNames.TSNE),
    // Time Series
    @JsonSubTypes.Type(value = ArimaTuningConfig.class, name = ModelNames.ARIMA),
    @JsonSubTypes.Type(value = SarimaxTuningConfig.class, name = ModelNames.SARIMAX)
})
public sealed interface TuningModelConfig extends TrainingModelSpec permits
    // Classification
    RandomForestClassifierTuningConfig,
    LogisticRegressionTuningConfig,
    SvmClassifierTuningConfig,
    LgbmClassifierTuningConfig,
    // Regression
    LinearRegressionTuningConfig,
    RidgeRegressionTuningConfig,
    RandomForestRegressorTuningConfig,
    LgbmRegressorTuningConfig,
    // Clustering
    KMeansTuningConfig,
    DbscanTuningConfig,
    AgglomerativeTuningConfig,
    // Dimensionality
    PcaTuningConfig,
    TsneTuningConfig,
    // Temporal
    ArimaTuningConfig,
    SarimaxTuningConfig {

    String modelType();

    boolean requiresTarget();

    ModelConfig.ModelCategory category();
}
